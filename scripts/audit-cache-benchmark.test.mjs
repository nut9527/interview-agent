import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { auditCacheMeasurements, auditHistoricalCacheEvidence } from './audit-cache-benchmark.mjs';

const original = JSON.parse(await readFile(new URL('../docs/evidence/middleware-batch1-baseline.json', import.meta.url)));

test('audits the restored historical evidence and recomputes raw percentiles', async () => {
  const result = await auditHistoricalCacheEvidence();
  assert.equal(result.reranBenchmark, false);
  assert.equal(result.groups.coldCache.p95, 4.97);
  assert.equal(result.groups.warmCache.p95, 2.9393);
  assert.equal(result.groups.warmCache.savedBodySourceReads, 0);
});

for (const [name, tamper] of [
  ['missing raw sample', report => report.coldCache.catalogMs.samples.pop()],
  ['changed tail sample', report => { report.warmCache.catalogMs.samples[0] = 99; }],
  ['invented percentile', report => { report.coldCache.catalogMs.p95 = 1; }],
  ['inconsistent hit ratio', report => { report.warmCache.hitRatio = .95; }],
  ['inconsistent source count', report => { report.warmCache.bodySourceReads = 1; }],
  ['negative duration', report => { report.coldCache.catalogMs.samples[0] = -1; }],
]) test(`rejects ${name}`, () => {
  const copy = structuredClone(original);
  tamper(copy);
  assert.throws(() => auditCacheMeasurements(copy));
});
