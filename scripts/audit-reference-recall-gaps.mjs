// 对比冻结的候选池与补入同一文档、同一标题相邻块后的候选池，核对遗漏的标注引文。
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, relative } from 'node:path';

const root = new URL('../', import.meta.url);
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--diagnostics'))
  throw new Error('Usage: node scripts/audit-reference-recall-gaps.mjs [--diagnostics <report.json>]');
const inputPath = args.length ? resolve(args[1]) : fileURLToPath(new URL('docs/evidence/reference-retrieval-diagnostics.json', root));
const input = relative(fileURLToPath(root), inputPath).replaceAll('\\', '/');
const bytes = await readFile(inputPath);
const data = JSON.parse(bytes).diagnostics;
const hash = value => createHash('sha256').update(value).digest('hex');
const chapters = new Map();
for (const [id, chunk] of Object.entries(data.chunks)) {
  const key = JSON.stringify([chunk.documentId, chunk.heading]);
  if (!chapters.has(key)) chapters.set(key, []);
  chapters.get(key).push(id);
}
const neighbors = new Map();
for (const ids of chapters.values()) {
  ids.sort((a, b) => data.chunks[a].startOffset - data.chunks[b].startOffset);
  ids.forEach((id, i) => neighbors.set(id, ids.slice(Math.max(0, i - 1), i + 2)));
}
const expand = ids => [...new Set(ids.flatMap(id => neighbors.get(id)))];
const stats = values => {
  values.sort((a, b) => a - b);
  return { min: values[0], median: values[Math.floor(values.length / 2)], max: values.at(-1) };
};
const details = [];
const summary = {};
for (const scope of ['selected', 'all']) {
  const rows = data.details.map(item => {
    const original = item.scopes[scope];
    const rrf = original.stages.rrfTop30.map(row => row.chunkId);
    const pools = { rrf30: rrf, unionUpTo60: original.candidateUnion,
      rrf30WithNeighbors: expand(rrf), unionWithNeighbors: expand(original.candidateUnion) };
    const variants = Object.fromEntries(Object.entries(pools).map(([name, ids]) => {
      const hits = item.gold.filter(g => g.chunkIds.some(id => ids.includes(id))).map(g => g.source);
      return [name, { candidates: ids.length, recall: item.gold.length ? hits.length / item.gold.length : null,
        hits, missing: item.gold.filter(g => !hits.includes(g.source)).map(g => g.source) }];
    }));
    return { id: item.id, scope, variants };
  });
  summary[scope] = Object.fromEntries(Object.keys(rows[0].variants).map(variant => {
    const positives = rows.filter(row => row.variants[variant].recall !== null);
    return [variant, { positiveCases: positives.length,
      macroRecall: positives.reduce((sum, row) => sum + row.variants[variant].recall, 0) / positives.length,
      candidateCount: stats(rows.map(row => row.variants[variant].candidates)),
      restoredSinceRrf30: positives.flatMap(row => {
        const restored = row.variants[variant].hits.filter(source => !row.variants.rrf30.hits.includes(source));
        return restored.length ? [{ id: row.id, sources: restored }] : [];
      }) }];
  }));
  assert.equal(summary[scope].rrf30.macroRecall,
    data.summary[scope].exactQuote.all.candidatePools.rrfTop30.recall);
  details.push(...rows);
}
const missingCases = data.details.filter(item => item.scopes.all.diagnostics.exactQuote.rrfTop30.absentFromPool.length);
const evidence = missingCases.map(item => ({ id: item.id, question: item.question,
  missingGold: item.gold.filter(g => item.scopes.all.diagnostics.exactQuote.rrfTop30.absentFromPool.includes(g.source))
    .map(g => ({ ...g, chunks: g.chunkIds.map(id => ({ id, ...data.chunks[id],
      neighbors: neighbors.get(id).filter(n => n !== id).map(n => ({ id: n, ...data.chunks[n] })) })) })) }));
const runId = randomUUID();
const output = new URL(`docs/evidence/reference-recall-gaps-${runId}.json`, root);
await writeFile(output, JSON.stringify({ schemaVersion: 1, runId,
  input: { path: input, sha256: hash(bytes) },
  sourceSha256: hash(await readFile(fileURLToPath(import.meta.url))),
  method: 'At most one previous and next evidence chunk within the same document and heading, ordered by source offset. Original chunks and labels remain unchanged.',
  interpretation: 'Candidate coverage only. Expanded pools have not been reranked, timed, or deployed.',
  summary, evidence, details }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output: fileURLToPath(output), summary }, null, 2));
