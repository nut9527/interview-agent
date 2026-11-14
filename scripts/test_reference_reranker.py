"""Check the Python experiment against the recorded Java production rankings and metrics."""
import json
from pathlib import Path
import runpy
import statistics
import unittest

ROOT = Path(__file__).resolve().parents[1]
EVAL = runpy.run_path(str(ROOT / "scripts/evaluate-reference-reranker.py"))
DATA = json.loads(EVAL["INPUT"].read_text(encoding="utf-8"))["diagnostics"]


class RecordedParityTest(unittest.TestCase):
    def test_metrics_match_java_for_both_scopes_and_criteria(self):
        for scope in EVAL["SCOPES"]:
            for criterion in ("chapter", "exactQuote"):
                for stage in ("lexicalTop30", "finalTop6"):
                    for cutoff in (1, 3, 6):
                        values = [EVAL["metrics"](
                            case, [r["chunkId"] for r in case["scopes"][scope]["stages"][stage]],
                            criterion, DATA["chunks"], cutoff)
                            for case in DATA["details"] if case["gold"]]
                        expected = DATA["summary"][scope][criterion]["all"]["stages"][stage]["atK"][str(cutoff)]
                        for metric in ("recall", "mrr", "ndcg"):
                            with self.subTest(scope=scope, criterion=criterion, stage=stage, k=cutoff, metric=metric):
                                self.assertAlmostEqual(statistics.mean(v[metric] for v in values), expected[metric])

    def test_quota_matches_all_206_production_results(self):
        for case in DATA["details"]:
            for scope in EVAL["SCOPES"]:
                stages = case["scopes"][scope]["stages"]
                scores = {}
                for stage in ("lexicalTop30", "vectorTop30"):
                    for row in stages[stage]:
                        scores[row["chunkId"]] = scores.get(row["chunkId"], 0) + 1 / (60 + row["rank"])
                def tie_key(chunk_id):
                    return DATA["chunks"][chunk_id]["documentId"] + ":1:" + chunk_id
                ordered = sorted(scores, key=lambda chunk_id: (-scores[chunk_id], tie_key(chunk_id)))
                expected = [r["chunkId"] for r in stages["finalTop6"]]
                with self.subTest(case=case["id"], scope=scope):
                    self.assertEqual(EVAL["diversify"](ordered, DATA["chunks"]), expected)
                    self.assertEqual(ordered[:30], [r["chunkId"] for r in stages["rrfTop30"]])


if __name__ == "__main__":
    unittest.main()
