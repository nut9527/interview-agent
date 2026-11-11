import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { makePlan, prepareReview, summarizeReview } from './agent-quality-review.mjs';

const fixturePath = new URL('../backend/src/test/resources/agent-evaluation.json', import.meta.url);
const fixtures = JSON.parse(await readFile(fixturePath, 'utf8'));
const plan = makePlan(fixtures);
const rows = (await Promise.all(['legacy', 'fixed', 'tool'].map(async route =>
  (await readFile(new URL(`../docs/evidence/agent-evaluation-${route}.jsonl`, import.meta.url), 'utf8'))
    .trim().split('\n').map(JSON.parse)))).flat();
const copy = value => structuredClone(value);
const scored = value => ({ reviewer: 'TEST ONLY (not evidence)', method: 'HUMAN_BLIND', reviewedAt: '2026-01-01T00:00:00Z',
  followUpRelevance: value, evidenceSupport: value, specificity: value,
  unsupportedCorrection: false, repeatedQuestion: false, notes: 'Synthetic test score; never exported as evidence.' });

test('freezes 30 unique non-heldout families and rejects family leakage', () => {
  assert.equal(plan.expectedRuns, 90);
  assert.equal(plan.cases.length, 30);
  assert.ok(plan.cases.every(c => c.partition !== 'heldout'));
  const leaked = copy(fixtures);
  leaked.find(f => f.partition === 'heldout').family = leaked[0].family;
  assert.throws(() => makePlan(leaked), /Family leakage/);
});

test('existing six outputs remain explicitly unscored and incomplete', () => {
  const pack = prepareReview(plan, rows);
  const summary = summarizeReview(plan, rows, pack.key, pack.review);
  assert.equal(summary.observedRuns, 6);
  assert.equal(summary.humanReviewedRuns, 0);
  assert.equal(summary.reviewStatus, 'INCOMPLETE_NO_QUALITY_CLAIM');
  assert.ok(summary.groups.every(g => g.missing === 28));
  assert.ok(summary.comparisons.every(c => c.pairedCases === 0 && c.meanDelta.followUpRelevance === null));
  for (const row of pack.review.rows) {
    assert.deepEqual(Object.keys(row).sort(), ['answer', 'assessment', 'blindId', 'nextQuestion', 'question', 'review', 'source']);
    assert.ok(!JSON.stringify(row).includes('runId'));
  }
});

test('duplicate runs, modified sources and heldout rows cannot enter the pack', () => {
  assert.throws(() => prepareReview(plan, [...rows, rows[0]]), /Duplicate case/);
  assert.throws(() => prepareReview(plan, [{ ...rows[0], sourceHash: 'changed' }]), /Frozen fixture/);
  assert.throws(() => prepareReview(plan, [{ ...rows[0], caseId: 'repair' }]), /Unregistered/);
});

test('partial scores and modified blind output are rejected', () => {
  let pack = prepareReview(plan, rows);
  pack.review.rows[0].review.followUpRelevance = 4;
  assert.throws(() => summarizeReview(plan, rows, pack.key, pack.review), /Partial\/invalid/);
  pack = prepareReview(plan, rows);
  pack.review.rows[0].nextQuestion = 'tampered';
  assert.throws(() => summarizeReview(plan, rows, pack.key, pack.review), /content changed/);
});

test('paired differences match the same case, config mismatches remain excluded', () => {
  const pack = prepareReview(plan, rows);
  for (const row of pack.review.rows) {
    const route = pack.key.find(k => k.blindId === row.blindId).route;
    row.review = scored({ LEGACY: 1, FIXED: 3, TOOL: 4 }[route]);
  }
  let summary = summarizeReview(plan, rows, pack.key, pack.review);
  const comparison = (r, baseline) => r.comparisons.find(c => c.partition === 'development' && c.baseline === baseline);
  assert.equal(comparison(summary, 'LEGACY').pairedCases, 2);
  assert.equal(comparison(summary, 'LEGACY').meanDelta.followUpRelevance, 2);
  assert.equal(comparison(summary, 'FIXED').meanDelta.followUpRelevance, 1);
  assert.equal(summary.reviewStatus, 'INCOMPLETE_NO_QUALITY_CLAIM');
  const changed = copy(rows);
  changed.find(r => r.route === 'TOOL').config.model.model = 'different-model';
  summary = summarizeReview(plan, changed, pack.key, pack.review);
  assert.equal(comparison(summary, 'FIXED').pairedCases, 1);
  assert.ok(comparison(summary, 'FIXED').excluded.some(e => e.reason === 'CONFIG_MISMATCH'));
});

test('failures remain in the denominator and have no invented human score', () => {
  const failed = copy(rows);
  failed[0].status = 'FAILED';
  const pack = prepareReview(plan, failed);
  const summary = summarizeReview(plan, failed, pack.key, pack.review);
  assert.equal(pack.review.rows.length, 5);
  assert.equal(summary.groups.find(g => g.route === failed[0].route).failed, 1);
  assert.equal(summary.groups.find(g => g.route === failed[0].route).observed, 2);
  assert.ok(summary.comparisons.some(c => c.excluded.some(e => e.reason === 'RUN_FAILED')));
});
