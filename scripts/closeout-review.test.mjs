import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, realpath, rm } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { openBudget } from './closeout-budget.mjs';
import { reviewTemplate, assessReview } from './closeout-review.mjs';
import { canonical } from './release-fixtures.mjs';
import { projectRoot, sha256 } from './release-candidate.mjs';

async function fixture(t, count = 2, invalidCitation = false) {
  const parent = join(projectRoot, '.runtime'); await mkdir(parent, { recursive: true });
  const directory = await mkdtemp(join(parent, 'closeout-review-test-'));
  const path = join(directory, 'budget.jsonl');
  let gate = await openBudget(path);
  t.after(async () => {
    await gate.close();
    const actual = await realpath(directory);
    assert.ok(actual.startsWith(await realpath(parent) + sep + 'closeout-review-test-'));
    await rm(actual, { recursive: true });
  });
  const price = { model: 'deepseek-flash', endpoint: 'https://api.deepseek.com/chat/completions', currency: 'CNY',
    contextTokenUpperBound: 1048576, inputMicroCnyPerToken: 2, outputMicroCnyPerToken: 8,
    source: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',
    verifiedAt: new Date(Date.now() - 1000).toISOString(), validUntil: new Date(Date.now() + 3600000).toISOString() };
  const rows = Array.from({ length: count }, (_, i) => ({ caseId: `sample-${i}`, runId: `run-${i}`,
    fixture: { id: `sample-${i}`, answer: '本人原文😀', material: '固定资料' },
    status: 'SUCCEEDED', config: { loopPolicy: 'B0', model: { model: price.model } },
    attempts: 1, attemptLedger: [{ ordinal: 1, status: 'SUCCEEDED' }], toolExecutions: i === 0 ? 1 : 0,
    receipts: i === 0 ? [{ tool_name: 'search_knowledge', result_json: '固定资料' }] : [],
    evaluation: { quote: '本人原文😀' }, nextQuestion: '请说明失败窗口',
    validationFailures: invalidCitation && i < 2 ? ['服务端校验未通过：引文不匹配'] : [],
  }));
  const plan = { batchId: 'closeout-1', sourceManifestSha256: 'b'.repeat(64), registrationSha256: 'c'.repeat(64), price,
    samples: rows.map((row, i) => ({ id: row.caseId, inputSha256: sha256(canonical(row.fixture)),
      toolExpectation: i === 0 ? 'REQUIRED' : 'ZERO_ALLOWED' })) };
  await gate.begin({ ...plan, sha256: sha256(canonical(plan)) });
  const wire = [];
  for (let i = 0; i < count; i++) {
    const body = Buffer.from(JSON.stringify({ model: price.model, stream: false, thinking: { type: 'disabled' },
      max_tokens: 2000, messages: [{ role: 'user', content: rows[i].fixture.answer }] }));
    await gate.exchange(`attempt-${i}`, body, async (request, attemptId) => {
      const common = { attemptId, planSha256: sha256(canonical(plan)), sourceManifestSha256: plan.sourceManifestSha256,
        requestSha256: sha256(request) };
      wire.push({ ...common, kind: 'REQUEST', body: request.toString('utf8') });
      const response = Buffer.from(JSON.stringify({ usage: { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 } }));
      wire.push({ ...common, kind: 'RESPONSE', status: 200, body: response.toString('utf8') });
      return { status: 200, body: response };
    });
  }
  const reportBytes = Buffer.from(rows.map(row => JSON.stringify(row)).join('\n') + '\n');
  const wireBytes = Buffer.from(wire.map(row => JSON.stringify(row)).join('\n') + '\n');
  await gate.finish('REVIEW_PENDING', { reportSha256: sha256(reportBytes), wireSha256: sha256(wireBytes) });
  const batch = gate.snapshot().batches[0];
  const document = reviewTemplate(plan, batch, rows);
  Object.assign(document.metadata, { reviewMethod: '开发者人工检查', reviewedAt: new Date().toISOString() });
  for (const row of document.rows) Object.assign(row, { citationSupport: '满足', followUpRelevance: '满足',
    repeatedQuestion: '无', ungroundedCorrection: '无', retryContinuity: '不适用',
    notApplicableReasons: { retryContinuity: '单轮样本；完整链路另行检查' },
    conclusion: '通过', reviewer: '合成测试检查人', evidence: ['/fixture/answer', '/evaluation/quote'] });
  return { get gate() { return gate; }, path, plan, batch, document, reportBytes, wireBytes,
    reopen: async () => { await gate.close(); gate = await openBudget(path); } };
}

test('complete review persists PASS and the exact review; reopening permits the next batch', async t => {
  const f = await fixture(t);
  assert.equal(assessReview(f.plan, f.batch, f.document, f.reportBytes, f.wireBytes).status, 'PASS');
  // 只检查不会改变账本。
  assert.equal(f.gate.snapshot().batches[0].status, 'REVIEW_PENDING');
  await f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes);
  await f.reopen();
  assert.equal(f.gate.snapshot().batches[0].status, 'PASS');
  assert.deepEqual(f.gate.snapshot().batches[0].review.document, f.document);
  await assert.rejects(f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes), /REVIEW_PENDING/);
  await f.gate.begin({ ...f.plan, batchId: 'closeout-2', sha256: 'd'.repeat(64) });
});

for (const [name, change] of [
  ['missing sample', d => d.rows.pop()],
  ['duplicate sample', d => { d.rows[1] = structuredClone(d.rows[0]); }],
  ['wrong candidate', d => { d.metadata.candidateFingerprint = 'd'.repeat(64); }],
  ['wrong run', d => { d.rows[0].runIdOrReason = 'unrelated-run'; }],
  ['missing reviewer', d => { d.rows[0].reviewer = ''; }],
  ['unexplained N/A', d => { d.rows[0].notApplicableReasons = {}; }],
  ['invented retry acceptance', d => { d.rows[0].retryContinuity = '满足'; }],
  ['missing evidence', d => { d.rows[0].evidence = ['/evaluation/absent']; }],
  ['empty receipts', d => { d.rows[1].evidence = ['/receipts']; }],
  ['concealed correction', d => { d.rows[0].ungroundedCorrection = '有'; }],
  ['concealed quality issue', d => { d.rows[0].followUpRelevance = '部分满足'; }],
  ['unfinished row', d => { d.rows[0].conclusion = '未完成'; }],
]) test(`review rejects ${name} without altering ledger`, async t => {
  const f = await fixture(t), before = await readFile(f.path);
  change(f.document);
  await assert.rejects(f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes));
  assert.deepEqual(await readFile(f.path), before);
});

test('changed report or wire cannot be accepted using the old hashes', async t => {
  const f = await fixture(t);
  await assert.rejects(f.gate.review(f.plan, f.document, Buffer.from('[]'), f.wireBytes), /evidence changed/);
  await assert.rejects(f.gate.review(f.plan, f.document, f.reportBytes, Buffer.from('[]')), /evidence changed/);
  const wire = f.wireBytes.toString().trim().split('\n').map(JSON.parse);
  wire[1].body = '{}'; const corrupted = Buffer.from(wire.map(JSON.stringify).join('\n'));
  const batch = { ...f.batch, evidence: { ...f.batch.evidence, wireSha256: sha256(corrupted) } };
  assert.throws(() => assessReview(f.plan, batch, f.document, f.reportBytes, corrupted), /settlement/);
});

test('unsupported references fail and severe accepted citations stop future spending', async t => {
  const f = await fixture(t);
  Object.assign(f.document.rows[0], { citationSupport: '不满足', conclusion: '阻断',
    blockingIssue: { id: 'CITE-1', category: '错误引用落库' } });
  const result = await f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes);
  assert.equal(result.status, 'FAIL'); assert.equal(result.stop, true);
  await assert.rejects(f.gate.begin({ ...f.plan, batchId: 'closeout-2', sha256: 'd'.repeat(64) }), /stopped/);
});

test('nonblocking quality observations are retained without pretending they are resolved', async t => {
  const f = await fixture(t);
  Object.assign(f.document.rows[0], { followUpRelevance: '部分满足', conclusion: '有非阻断问题', notes: '问题范围过宽，留作质量限制。' });
  assert.equal((await f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes)).status, 'PASS');
});

test('over twenty percent citation rejections after five samples stops expansion', async t => {
  const f = await fixture(t, 5, true);
  const result = await f.gate.review(f.plan, f.document, f.reportBytes, f.wireBytes);
  assert.equal(result.status, 'FAIL'); assert.equal(result.stop, true);
  assert.equal(result.citationRejectedSamples, 2);
});
