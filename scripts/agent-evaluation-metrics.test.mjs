import test from 'node:test';
import assert from 'node:assert/strict';
import { checkWorkerEvidence, summarizeAgentRows } from './agent-evaluation-metrics.mjs';
const row = fields => ({ status: 'SUCCEEDED', attempts: 1, toolExecutions: 1, attemptLedger: [], receipts: [], elapsedMs: 10, ...fields });
test('required retrieval needs a completed receipt with usable evidence even when the worker succeeds', () => {
  const sample = { id: 'redis-order', toolExpectation: 'REQUIRED' };
  const result = row({ caseId: sample.id, sourceHash: 'fixture-hash',
    fixture: { reasonableTools: ['search_knowledge', 'read_material_section'], evidenceQuote: '原文' },
    evaluation: { round: 1 }, nextQuestion: '如何验证？' });
  assert.equal(checkWorkerEvidence(result, sample), 'MISSING_REQUIRED_TOOL_EVIDENCE');
  const receipt = { tool_name: 'search_knowledge', execution_kind: 'EXECUTED',
    result_json: JSON.stringify({ status: 'FOUND', references: [{ sha256: result.sourceHash, excerpts: [{ content: '原文' }] }] }) };
  for (const unusable of [{ execution_kind: 'SKIPPED' }, { tool_name: 'other_tool' },
    { result_json: '{"status":"EMPTY"}' }, { result_json: '{"status":"FOUND","references":[]}' }]) {
    assert.equal(checkWorkerEvidence({ ...result, receipts: [{ ...receipt, ...unusable }] }, sample), 'MISSING_REQUIRED_TOOL_EVIDENCE');
  }
  assert.equal(checkWorkerEvidence({ ...result, receipts: [receipt] }, sample), 'PASSED');
  const probe = { ...result, initialKnowledgeExcerpts: 'WITHHELD' };
  assert.equal(checkWorkerEvidence({ ...probe, receipts: [receipt] }, sample), 'MISSING_KNOWLEDGE_CITATION');
  const reference = { id: 'doc', version: 1, sha256: result.sourceHash, excerpts: [{ chunkId: 'chunk', content: '这是原文' }] };
  const evidence = { evidenceId: 'cite', validity: 'VALID', sourceType: 'KNOWLEDGE_EXCERPT', sourceId: 'knowledge/doc/chunk',
    sourceVersion: '1@fixture-hash', offsetStart: 2, offsetEnd: 4, quote: '原文' };
  const evaluation = { knowledgeSources: [reference], assessment: { detail: {
    evidence: [evidence], items: [{ citations: [{ evidenceId: 'cite' }] }] } } };
  assert.equal(checkWorkerEvidence({ ...probe, evaluation, receipts: [receipt] }, sample), 'PASSED');
  evidence.offsetStart = 1;
  assert.equal(checkWorkerEvidence({ ...probe, evaluation, receipts: [receipt] }, sample), 'MISSING_KNOWLEDGE_CITATION');
  assert.equal(checkWorkerEvidence({ ...probe, sourceHash: 'stale', receipts: [receipt] }, sample), 'MISSING_REQUIRED_TOOL_EVIDENCE');
  const section = { ...receipt, tool_name: 'read_material_section', result_json: '{"status":"FOUND","content":"原文"}' };
  assert.equal(checkWorkerEvidence({ ...probe, receipts: [section] }, sample), 'MISSING_REQUIRED_TOOL_EVIDENCE');
  assert.equal(checkWorkerEvidence({ ...result, receipts: [section] }, sample), 'PASSED');
  assert.equal(checkWorkerEvidence(result, { ...sample, toolExpectation: 'ZERO_ALLOWED' }), 'PASSED');
  assert.equal(checkWorkerEvidence({ ...result, evaluation: null }, sample), 'MISSING_COMMITTED_OUTPUT');
});
test('all accepted runs remain in denominator; reservations are not requests or executions', () => {
  const m = summarizeAgentRows([row({ modelToolRequests: [] }), row({ status: 'CANCELLED', modelToolRequests: [] }), row({ status: 'RUNNING', modelToolRequests: [] })]);
  assert.equal(m.runSuccess.value, 1 / 3);
  assert.equal(m.unfinished, 1);
  assert.equal(m.tools.reserved, 3);
  assert.equal(m.toolRequests.calls, 0);
  assert.equal(m.toolRequests.responseRate, null);
  assert.equal(m.firstFinalOutput.unknownRuns, 3);
  assert.equal(m.tools.executionSuccessRate, null);
});
test('tool response rate counts responses, while EMPTY, cache and unknown receipts stay separate', () => {
  const m = summarizeAgentRows([row({ attemptResponses: [{ toolCallCount: 2 }, { toolCallCount: 0 }],
    modelToolRequests: [{}, {}], firstFinalOutputValid: false,
    receipts: [{ execution_kind: 'EXECUTED', tool_name: 'search_knowledge', result_json: '{"status":"EMPTY"}' },
      { execution_kind: 'CACHE', tool_name: 'search_knowledge', result_json: '{"status":"FOUND"}' },
      { result_json: '{"status":"FOUND"}' }] })]);
  assert.equal(m.toolRequests.responseRate.value, 0.5);
  assert.equal(m.firstFinalOutput.legalityAmongKnown.value, 0);
  assert.equal(m.tools.completedExecutedReceipts, 1);
  assert.equal(m.tools.retrievalHitAmongCompleted.value, 0);
  assert.equal(m.tools.cacheReceipts, 1);
  assert.equal(m.tools.unknownKindReceipts, 1);
});
test('empty denominators are unknown instead of manufactured zero rates', () => {
  const m = summarizeAgentRows([]);
  assert.equal(m.runSuccess.value, null);
  assert.equal(m.toolRequests.responseRate.value, null);
  assert.equal(m.firstFinalOutput.legalityAmongKnown.value, null);
});
test('missing budgets stay unknown; a transport failure may also have unknown usage', () => {
  const m = summarizeAgentRows([row({ attempts: undefined, toolExecutions: undefined,
    attemptLedger: [{ status: 'UNKNOWN', error_code: 'MODEL_NETWORK_SSLHANDSHAKEEXCEPTION' }] })]);
  assert.equal(m.attempts.registered, null);
  assert.equal(m.tools.reserved, null);
  assert.equal(m.attempts.transportFailed, 1);
  assert.equal(m.attempts.unknown, 1);
});
