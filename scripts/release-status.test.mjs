import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { describeCandidate, readBudgetHistory, readReleaseStatus } from './release-status.mjs';
import { projectRoot, sha256 } from './release-candidate.mjs';

test('inventory retains failed history and partial success without claiming AI release acceptance', async () => {
  const result = await readReleaseStatus();
  assert.equal(result.checkMeaning, 'EVIDENCE_INVENTORY_ONLY');
  assert.equal(result.registrationStatus, 'CANDIDATE_NOT_ACCEPTED');
  assert.equal(result.releaseAccepted, false);
  assert.equal(result.registeredSamples, 12);
  assert.equal(result.registeredFlow.interviewRounds, 6);
  assert.equal(result.registeredFlow.retryRounds, 3);
  assert.equal(result.historicalCloseoutBatches[0].status, 'FAIL');
  assert.equal(result.partialCitationFix.result, 'PASSED');
  assert.equal(result.partialCitationFix.samples, 1);
  assert.equal(result.kernelHeldoutExecuted, false);
  assert.equal(result.candidateSourceManifest, null);
  assert.equal(result.remainingRunnerWork.length, 3);
});

test('a passing historical batch or renamed registration cannot promote a candidate', async () => {
  const registration = JSON.parse(await readFile(join(projectRoot, 'contracts/release-v1.0.json'), 'utf8'));
  const history = { batches: [{ id: 'closeout-1', status: 'PASS', sourceManifestSha256: 'old-candidate' }] };
  const partial = { result: 'PASSED', runs: [{}] };
  assert.equal(describeCandidate(registration, history, { heldoutExecuted: true }, partial).releaseAccepted, false);
  for (const status of ['ACCEPTED', 'PASSED', 'RELEASE_ACCEPTED']) {
    assert.throws(() => describeCandidate({ ...registration, status }, history, {}, partial), /Unsupported release decision/);
  }
});

test('read-only history validation rejects partial writes, tampering and a broken chain', async () => {
  const raw = await readFile(join(projectRoot, 'docs/evidence/closeout-model-budget.jsonl'), 'utf8');
  assert.equal(readBudgetHistory(raw).batches[0].status, 'FAIL');
  assert.throws(() => readBudgetHistory(raw.trimEnd()), /incomplete/);
  assert.throws(() => readBudgetHistory(raw.replace('FAIL', 'PASS')), /hash mismatch/);
  const lines = raw.trim().split('\n');
  assert.throws(() => readBudgetHistory(lines.slice(1).join('\n') + '\n'), /hash mismatch/);
  const state = { campaign: 'other', batches: [] };
  const row = { sequence: 1, previous: '0'.repeat(64), state };
  const invalid = JSON.stringify({ ...row, sha256: sha256(JSON.stringify(row)) }) + '\n';
  assert.throws(() => readBudgetHistory(invalid), /Invalid closeout campaign/);
});
