"""Paired reranker experiment on frozen retrieval candidates; no application API changes."""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import random
import runpy
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / "docs/evidence/reference-retrieval-diagnostics.json"
SETUP = runpy.run_path(str(ROOT / "scripts/prepare-reference-reranker.py"))
MODES = ("lexical6", "lexical30Quota6", "productionHybrid6", "rrf30Quota6", "reranked30Quota6")
SCOPES = ("selected", "all")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def diversify(ordered, chunks):
    counts, chosen, deferred = Counter(), [], []
    for chunk_id in ordered:
        chunk = chunks[chunk_id]
        section = (chunk["documentId"], chunk["heading"].strip() or chunk_id)
        if counts[section] < 2:
            chosen.append(chunk_id)
            counts[section] += 1
            if len(chosen) == 6:
                return chosen
        else:
            deferred.append(chunk_id)
    return (chosen + deferred)[:6]


def relevant(gold, chunk_id, criterion, chunks):
    chunk = chunks[chunk_id]
    return (chunk_id in gold["chunkIds"] if criterion == "exactQuote"
            else gold["source"] == chunk["documentId"] + ":" + chunk["heading"])


def metrics(case, ranking, criterion, chunks, k=6):
    seen, first, dcg = set(), 0, 0.0
    for rank, chunk_id in enumerate(ranking[:k], 1):
        for gold in case["gold"]:
            if gold["source"] not in seen and relevant(gold, chunk_id, criterion, chunks):
                seen.add(gold["source"])
                first = first or rank
                dcg += 1 / math.log2(rank + 1)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(k, len(case["gold"]))))
    return {"recall": len(seen) / len(case["gold"]),
            "mrr": 1 / first if first else 0, "ndcg": dcg / ideal}


def distribution(values):
    ordered = sorted(values)
    return {"samples": len(values), "p50Ms": statistics.median(values),
            "p95Ms": ordered[math.ceil(len(values) * .95) - 1],
            "minMs": ordered[0], "maxMs": ordered[-1]}


class Reranker:
    def __init__(self):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        self.np = np
        ort.disable_telemetry_events()
        self.tokenizer = Tokenizer.from_file(str(SETUP["MODEL_DIR"] / "tokenizer.json"))
        self.tokenizer.no_padding()
        self.tokenizer.enable_truncation(max_length=512, strategy="only_second")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(str(SETUP["MODEL_DIR"] / "model_quantized.onnx"),
                                           sess_options=options, providers=["CPUExecutionProvider"])
        assert {i.name for i in self.session.get_inputs()} == {"input_ids", "attention_mask"}

    def score(self, query, texts):
        encoded = self.tokenizer.encode_batch([(query, text) for text in texts])
        scores = []
        for start in range(0, len(encoded), 8):
            batch = encoded[start:start + 8]
            width = max(len(e.ids) for e in batch)
            ids = self.np.full((len(batch), width), 1, dtype=self.np.int64)
            mask = self.np.zeros_like(ids)
            for i, item in enumerate(batch):
                ids[i, :len(item.ids)] = item.ids
                mask[i, :len(item.ids)] = item.attention_mask
            logits = self.session.run(None, {"input_ids": ids, "attention_mask": mask})[0]
            scores.extend(float(x) for x in logits.reshape(-1))
        assert all(math.isfinite(score) for score in scores)
        return scores, encoded


def worker(args, data):
    model = Reranker()
    cases = data["diagnostics"]["details"][args.start:args.start + args.count]
    chunks = data["diagnostics"]["chunks"]
    first = cases[0]
    first_pool = first["scopes"]["selected"]["stages"]["rrfTop30"]

    def text(chunk_id):
        c = chunks[chunk_id]
        return c["heading"] + "\n" + c["content"] if c["heading"].strip() else c["content"]

    model.score(first["question"], [text(row["chunkId"]) for row in first_pool])
    output = []
    for index, case in enumerate(cases):
        # 逐题交替测量选定资料和全库，使预热和执行顺序的影响分摊到两组。
        scopes = SCOPES if (args.start + index) % 2 == 0 else tuple(reversed(SCOPES))
        for scope in scopes:
            original = case["scopes"][scope]
            pool = [row["chunkId"] for row in original["stages"]["rrfTop30"]]
            lexical = [row["chunkId"] for row in original["stages"]["lexicalTop30"]]
            texts = [text(chunk_id) for chunk_id in pool]
            baseline_start = time.perf_counter_ns()
            baseline = diversify(pool, chunks)
            baseline_ms = (time.perf_counter_ns() - baseline_start) / 1e6
            times, all_scores, all_rankings = [], [], []
            for _ in range(args.rounds):
                start = time.perf_counter_ns()
                scores, encoded = model.score(case["question"], texts)
                ranked = [pool[i] for i in sorted(range(len(pool)), key=lambda i: (-scores[i], i))]
                final = diversify(ranked, chunks)
                times.append((time.perf_counter_ns() - start) / 1e6)
                all_scores.append(scores)
                all_rankings.append(ranked)
            assert all(r == all_rankings[0] for r in all_rankings), (case["id"], scope)
            truncated = []
            hidden_gold = []
            for chunk_id, passage, encoding in zip(pool, texts, encoded):
                end = max(end for seq, (_, end) in zip(encoding.sequence_ids, encoding.offsets) if seq == 1)
                if encoding.overflowing:
                    truncated.append(chunk_id)
                for gold in case["gold"]:
                    if chunk_id in gold["chunkIds"] and gold["quote"] not in passage[:end]:
                        hidden_gold.append({"source": gold["source"], "chunkId": chunk_id})
            output.append({"id": case["id"], "scope": scope, "split": case["split"], "kind": case["kind"],
                           "rankings": {"lexical6": lexical[:6], "lexical30Quota6": diversify(lexical, chunks),
                                        "productionHybrid6": [r["chunkId"] for r in original["stages"]["finalTop6"]],
                                        "rrf30Quota6": baseline, "reranked30Quota6": final},
                           "reranked30": all_rankings[0],
                           "candidateScores": [{"chunkId": chunk_id, "logit": all_scores[0][i]}
                                               for i, chunk_id in enumerate(pool)],
                           "rerankMs": times, "baselineSelectionMs": baseline_ms,
                           "maxScoreDifferenceBetweenRounds": max(abs(a - b) for row in all_scores
                                                                   for a, b in zip(row, all_scores[0])),
                           "truncatedChunks": truncated, "goldQuotesOutsideModelInput": hidden_gold})
            print(f"{case['id']} {scope}: {statistics.median(times):.0f} ms", flush=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False), encoding="utf-8")


def summarize(rows, data):
    cases = {c["id"]: c for c in data["diagnostics"]["details"]}
    chunks = data["diagnostics"]["chunks"]

    def group(selected, criterion):
        positives = [r for r in selected if cases[r["id"]]["gold"]]
        modes = {}
        for mode in MODES:
            values = [metrics(cases[r["id"]], r["rankings"][mode], criterion, chunks) for r in positives]
            modes[mode] = {key: statistics.mean(v[key] for v in values) for key in ("recall", "mrr", "ndcg")}
        changes = {}
        for baseline in ("rrf30Quota6", "productionHybrid6", "lexical6", "lexical30Quota6"):
            gains, losses, improved_mrr, worsened_mrr, fixed, partial = [], [], [], [], [], []
            for row in positives:
                case = cases[row["id"]]
                before = metrics(case, row["rankings"][baseline], criterion, chunks)
                after = metrics(case, row["rankings"]["reranked30Quota6"], criterion, chunks)
                if after["recall"] > before["recall"]:
                    gains.append(row["id"])
                if after["recall"] < before["recall"]:
                    losses.append(row["id"])
                if after["mrr"] > before["mrr"]:
                    improved_mrr.append(row["id"])
                if after["mrr"] < before["mrr"]:
                    worsened_mrr.append(row["id"])
                targets = case["scopes"][row["scope"]]["diagnostics"][criterion]["rrfTop30"]["inPoolOutsideTop6"]
                recovered = [source for source in targets if any(
                    gold["source"] == source and any(relevant(gold, chunk_id, criterion, chunks)
                                                      for chunk_id in row["rankings"]["reranked30Quota6"])
                    for gold in case["gold"])]
                if targets and len(recovered) == len(targets):
                    fixed.append(row["id"])
                elif recovered:
                    partial.append(row["id"])
            changes[baseline] = {"recallImproved": gains, "recallRegressed": losses,
                                 "mrrImproved": improved_mrr, "mrrRegressed": worsened_mrr}
        return {"cases": len(selected), "positiveCases": len(positives), "at6": modes,
                "pairedChanges": changes, "originalTop30FailuresRecovered": fixed,
                "originalTop30FailuresPartlyRecovered": partial}

    result = {}
    for scope in SCOPES:
        scoped = [r for r in rows if r["scope"] == scope]
        criteria = {}
        for criterion in ("chapter", "exactQuote"):
            criteria[criterion] = {"all": group(scoped, criterion), "bySplit": {
                split: group([r for r in scoped if r["split"] == split], criterion)
                for split in ("development", "validation")}}
        result[scope] = {"quality": criteria, "rerankLatency": distribution([t for r in scoped for t in r["rerankMs"]]),
                         "baselineSelectionLatency": distribution([r["baselineSelectionMs"] for r in scoped]),
                         "productionVsFrozenBaselineChanged": [r["id"] for r in scoped
                            if r["rankings"]["productionHybrid6"] != r["rankings"]["rrf30Quota6"]],
                         "truncatedPairs": sum(len(r["truncatedChunks"]) for r in scoped),
                         "goldQuotesOutsideModelInput": [{"id": r["id"], **g} for r in scoped
                                                          for g in r["goldQuotesOutsideModelInput"]],
                         "noAnswerNonempty": sum(bool(r["rankings"]["reranked30Quota6"])
                                                 for r in scoped if not cases[r["id"]]["gold"])}
        differences = [metrics(cases[r["id"]], r["rankings"]["reranked30Quota6"], "exactQuote", chunks)["recall"]
                       - metrics(cases[r["id"]], r["rankings"]["rrf30Quota6"], "exactQuote", chunks)["recall"]
                       for r in scoped if cases[r["id"]]["gold"]]
        rng = random.Random(20261113)
        bootstraps = sorted(statistics.mean(rng.choices(differences, k=len(differences))) for _ in range(10000))
        result[scope]["pairedRecallDelta"] = {"mean": statistics.mean(differences),
            "bootstrap95": [bootstraps[249], bootstraps[9749]], "resamples": 10000, "seed": 20261113,
            "unit": "question; this interval does not model correlation within source families"}
    return result


def run(args, data):
    for name, expected in SETUP["FILES"].items():
        assert digest(SETUP["MODEL_DIR"] / Path(name).name) == expected, name
    directory = Path(tempfile.mkdtemp(prefix="reranker-evaluation-", dir=ROOT / ".runtime"))
    try:
        rows = []
        for start in range(0, len(data["diagnostics"]["details"]), 5):
            part = directory / f"part-{start}.json"
            command = [sys.executable, "-B", str(Path(__file__).resolve()), "--worker", "--start", str(start),
                       "--count", "5", "--rounds", str(args.rounds), "--output", str(part),
                       "--diagnostics", str(args.diagnostics)]
            for attempt in (1, 2):
                try:
                    subprocess.run(command, cwd=ROOT, check=True, timeout=300,
                                   env={**os.environ, "TOKENIZERS_PARALLELISM": "false", "PYTHONUTF8": "1",
                                        "TMP": str(directory), "TEMP": str(directory)})
                    break
                except subprocess.TimeoutExpired:
                    if attempt == 2:
                        raise
                    print(f"Retrying timed-out cases {start}..{start + 4}", flush=True)
            rows.extend(json.loads(part.read_text(encoding="utf-8")))
        assert len(rows) == len(data["diagnostics"]["details"]) * len(SCOPES)
        summary = summarize(rows, data)
        run_id = uuid4().hex
        report = {"schemaVersion": 1, "runId": run_id,
                  "input": {"path": Path(os.path.relpath(args.diagnostics, ROOT)).as_posix(),
                            "sha256": digest(args.diagnostics)},
                  "model": {"base": "BAAI/bge-reranker-base", "repository": SETUP["REPOSITORY"],
                            "revision": SETUP["REVISION"], "files": SETUP["FILES"], "format": "quantized ONNX"},
                  "configuration": {"candidateCount": 30, "finalCount": 6, "maxPerHeading": 2,
                                    "maxTokens": 512, "truncation": "only_second", "batchSize": 8,
                                    "intraOpThreads": 2, "interOpThreads": 1, "rounds": args.rounds,
                                    "passage": "heading + newline + content, same as production embeddingText",
                                    "tuning": "One preselected configuration; no tuning on either split."},
                  "measurement": {"timing": "Tokenization, padding, CPU inference, sorting and quota selection for 30 frozen candidates; excludes initial retrieval, model loading and per-worker warmup.",
                                  "baselineTiming": "Quota selection over already ranked RRF candidates; not total retrieval latency.",
                                  "labels": "Original chapter and exact-quote labels; alternative unjudged evidence is not added.",
                                  "warmup": "One complete 30-pair query per worker, excluded from measurements.",
                                  "noAnswer": "Nonempty retrieval is not an answer-generation error rate."},
                  "environment": {"python": sys.version, "platform": platform.platform(),
                                  "cpu": data["environment"]["cpu"], "logicalProcessors": os.cpu_count(),
                                  "packages": {p: importlib.metadata.version(p) for p in ("onnxruntime", "tokenizers", "numpy")}},
                  "sourceSha256": {p: digest(ROOT / p) for p in ("scripts/evaluate-reference-reranker.py",
                                    "scripts/prepare-reference-reranker.py", "scripts/reranker-requirements.txt",
                                    "scripts/test_reference_reranker.py")},
                  "summary": summary, "details": rows}
        output = ROOT / "docs/evidence" / ("reference-reranker-" + run_id + ".json")
        with output.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(json.dumps({"report": str(output), "cases": len(rows)}, ensure_ascii=False), flush=True)
    finally:
        assert directory.resolve().is_relative_to((ROOT / ".runtime").resolve())
        shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diagnostics", type=Path, default=DEFAULT_INPUT,
                        help="Java diagnostic report generated by evaluate-reference-retrieval.mjs --diagnostics")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--start", type=int, default=0, help=argparse.SUPPRESS)
    parser.add_argument("--count", type=int, default=5, help=argparse.SUPPRESS)
    parser.add_argument("--output", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    assert args.rounds > 0
    args.diagnostics = args.diagnostics.resolve()
    data = json.loads(args.diagnostics.read_text(encoding="utf-8"))
    (worker if args.worker else run)(args, data)


if __name__ == "__main__":
    main()
