// Recompute historical cache measurements. No database, Redis, .env or model access.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const evidencePath = 'docs/evidence/middleware-batch1-2026-11-04.json';
const originalSha256 = '667b7df9a9cf998d1174787dbaeb7ce7256687943713689e70228442b84b3836';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const close = (actual, expected, label) => {
  assert.ok(Number.isFinite(actual) && Math.abs(actual - expected) <= 1e-9, `${label}: expected ${expected}, got ${actual}`);
};

export function auditCacheMeasurements(report) {
  assert.equal(report.schemaVersion, 1);
  assert.equal(report.measuredAt, '2026-11-04T05:26:09.716789800Z');
  assert.equal(report.environment?.model, 'mock');
  const groups = {};
  for (const name of ['coldCache', 'warmCache']) {
    const group = report[name], recorded = group.catalogMs;
    assert.equal(recorded.count, 50, `${name}: expected 50 serial calls`);
    assert.equal(recorded.samples.length, recorded.count, `${name}: sample count`);
    assert.ok(recorded.samples.every(value => Number.isFinite(value) && value >= 0), `${name}: invalid duration`);
    const sorted = [...recorded.samples].sort((a, b) => a - b);
    const recomputed = {
      count: sorted.length,
      mean: sorted.reduce((sum, value) => sum + value, 0) / sorted.length,
      p50: sorted[Math.ceil(sorted.length * .50) - 1],
      p95: sorted[Math.ceil(sorted.length * .95) - 1],
      max: sorted.at(-1),
    };
    for (const [stat, value] of Object.entries(recomputed)) close(recorded[stat], value, `${name}.${stat}`);
    for (const key of ['hits', 'bodySourceReads']) {
      assert.ok(Number.isInteger(group[key]) && group[key] >= 0 && group[key] <= recorded.count, `${name}.${key}`);
    }
    // These are saved MeterRegistry deltas, not per-request DB traces.
    assert.equal(group.hits + group.bodySourceReads, recorded.count, `${name}: serial hit/source accounting`);
    close(group.hitRatio, group.hits / recorded.count, `${name}.hitRatio`);
    assert.equal(group.hits, name === 'coldCache' ? 0 : 50, `${name}: historical hit count`);
    assert.equal(group.bodySourceReads, name === 'coldCache' ? 50 : 0, `${name}: historical source count`);
    groups[name] = { ...recomputed, savedHits: group.hits, savedBodySourceReads: group.bodySourceReads,
      recomputedHitRatio: group.hits / recorded.count };
  }
  return groups;
}

export async function auditHistoricalCacheEvidence() {
  const bytes = await readFile(new URL(evidencePath, root));
  assert.equal(sha256(bytes), originalSha256, 'Historical evidence bytes differ from archived manifest');
  const report = JSON.parse(bytes);
  const groups = auditCacheMeasurements(report);
  const sourceSnapshots = [];
  for (const [path, archivedSha256] of [
    ['backend/src/test/java/cn/edu/whu/zhizhida/middleware/SplitRuntimeIT.java', '61871106cbb795bea9f2c50f7f886c0aa41a59ba8ff5eb5e1af316d75fc033ac'],
    ['backend/src/main/java/cn/edu/whu/zhizhida/questionbank/QuestionSnapshotCache.java', 'a1790501596aa2deaf707782f722cd1ff631179bde94d6c5f8675ccb992ebe9a'],
    ['backend/src/main/java/cn/edu/whu/zhizhida/questionbank/DefaultQuestionCatalog.java', 'b95af0207f87eb68fe9412361278d9e40dbef8826a02fc04b6cce3f385d3ba6e'],
    ['backend/src/main/java/cn/edu/whu/zhizhida/questionbank/MybatisPlusQuestionRepository.java', '7334b820e085b4f4b9424e517a87fb133bbd57961317fd818927216c3f44af71'],
  ]) {
    const currentSha256 = sha256(await readFile(new URL(path, root)));
    sourceSnapshots.push({ path, archivedSha256, currentSha256, sameBytesAsArchivedSource: currentSha256 === archivedSha256 });
  }
  return {
    schemaVersion: 1, audit: 'PASSED', auditedAt: new Date().toISOString(),
    measuredAt: report.measuredAt, reranBenchmark: false,
    evidence: { path: evidencePath, sha256: originalSha256,
      restoredFrom: 'outputs/release/closeout-batch1-evidence-20261105-01/payload/' + evidencePath,
      provenance: 'Byte-for-byte historical archive copy; hash also matched backend/target/middleware-benchmark.json during restoration.' },
    originalBenchmark: {
      archivedPath: 'outputs/release/closeout-batch1-evidence-20261105-01/payload/backend/src/test/java/cn/edu/whu/zhizhida/middleware/MiddlewareBenchmarkIT.java',
      archivedSha256: '163f95161d685d3b72eb0d002ea4c0213223c4ce6551358b70dd3d3c259ce05e',
      currentLocation: 'backend/src/test/java/cn/edu/whu/zhizhida/middleware/SplitRuntimeIT.java#Benchmark',
      note: 'The benchmark was subsequently consolidated into SplitRuntimeIT.Benchmark; raw evidence retains its original date.',
    },
    scope: 'Historical local integration measurement: real MySQL/Redis, mock AI; 50 serial catalog calls per group, one question/version.',
    percentileMethod: 'nearest-rank: sorted[ceil(n * q) - 1]', groups, sourceSnapshots,
    limitations: [
      'Percentiles/mean/max/count recomputed from all 100 raw durations. Hit ratio recomputed from saved hit deltas.',
      'Source/hit deltas are aggregate Micrometer observations asserted by the original integration test, not independently reconstructed SQL traces.',
      'Cold calls delete the snapshot key before each call; delete duration is excluded. Warm calls reuse the last cold fill. Both groups still query publication/version in MySQL.',
      'Catalog timing is not HTTP latency; source count is snapshot-body fallback count, not total DB queries or DB QPS.',
      'This is a one-key local baseline, not a realistic workload hit rate, concurrent load capacity, or a fresh benchmark of current source.',
      'Archived source hashes identify the available release archive, not a signed runtime attestation or a guaranteed exact execution revision.',
    ],
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  assert.ok(args.length === 0 || (args.length === 1 && args[0] === '--write'), 'Usage: node scripts/audit-cache-benchmark.mjs [--write]');
  const result = await auditHistoricalCacheEvidence();
  if (args.includes('--write')) await writeFile(new URL('docs/evidence/cache-benchmark-audit.json', root), JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result, null, 2));
}
