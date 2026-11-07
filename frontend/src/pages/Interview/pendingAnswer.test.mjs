import test from 'node:test';
import assert from 'node:assert/strict';
import {
  getPendingAnswer, pendingResult, pruneExpiredPendingAnswers,
  rememberPendingAnswer,
} from './pendingAnswer.ts';

const values = new Map();
globalThis.sessionStorage = {
  get length() { return values.size; },
  key(index) { return [...values.keys()][index] ?? null; },
  getItem(key) { return values.get(key) ?? null; },
  setItem(key, value) { values.set(key, value); },
  removeItem(key) { values.delete(key); },
};
globalThis.window = { setTimeout() { return 1; } };

test('pending answer is isolated by user, session and turn, then expires', () => {
  values.clear();
  const answer = rememberPendingAnswer(42, {
    sessionId: 'session-a', expectedTurn: 2, answer: '我的回答', requestId: 'request_001',
  });
  assert.deepEqual(getPendingAnswer(42, 'session-a'), answer);
  assert.equal(getPendingAnswer(43, 'session-a'), null);
  assert.equal(getPendingAnswer(42, 'session-b'), null);
  assert.ok(values.has('interview:pending:42:session-a:2'));
  values.set('interview:pending:42:session-a:2', JSON.stringify({ ...answer, expiresAt: Date.now() - 1 }));
  pruneExpiredPendingAnswers();
  assert.equal(getPendingAnswer(42, 'session-a'), null);
  assert.equal(values.size, 0);
});

test('server state chooses replay, waiting, retry or stale', () => {
  const answer = {
    sessionId: 'session-a', expectedTurn: 1, answer: '回答', requestId: 'request_001', expiresAt: Date.now() + 1000,
  };
  const session = { state: 'WAITING_ANSWER', turnCount: 1, turns: [], answerLeaseUntil: null };
  assert.equal(pendingResult(session, answer), 'retry');
  assert.equal(pendingResult({ ...session, answerLeaseUntil: new Date(Date.now() + 30_000).toISOString() }, answer), 'waiting');
  assert.equal(pendingResult({ ...session, turnCount: 2 }, answer), 'stale');
  assert.equal(pendingResult({ ...session, turns: [{ speaker: 'user', requestId: answer.requestId }] }, answer), 'committed');
});

test('late-round rejected answers survive reload with the same content and idempotency key', () => {
  values.clear();
  const answer = rememberPendingAnswer(42, {
    sessionId: 'long-interview', expectedTurn: 12, answer: '完整回答'.repeat(300), requestId: 'request_round_12',
  });
  const restored = getPendingAnswer(42, 'long-interview');
  assert.deepEqual(restored, answer);
  assert.equal(pendingResult({ state: 'WAITING_ANSWER', turnCount: 12, turns: [], answerLeaseUntil: null }, restored), 'retry');
});
