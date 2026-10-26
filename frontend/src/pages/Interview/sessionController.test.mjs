import test, { beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createSessionController } from './sessionController.ts';
import { ApiError } from '../../api/ApiError.ts';
import { resetAccountScope } from '../../api/accountScope.ts';
import { getPendingAnswer, rememberPendingAnswer, rememberActiveSession, getActiveSession } from './pendingAnswer.ts';

const values = new Map();
globalThis.sessionStorage = {
  get length() { return values.size; }, key: (index) => [...values.keys()][index] ?? null,
  getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
  removeItem: (key) => values.delete(key),
};
const controllers = [];
beforeEach(() => values.clear());
afterEach(() => { controllers.forEach((controller) => controller.dispose()); controllers.length = 0; resetAccountScope(); });
const session = (extra = {}) => ({
  sessionId: 'session-a', state: 'WAITING_ANSWER', turnCount: 1, turns: [], answerLeaseUntil: null,
  ...extra,
});
const pending = () => rememberPendingAnswer(42, {
  sessionId: 'session-a', expectedTurn: 1, answer: 'my original answer', requestId: 'request_001',
});
const make = (api = {}, changed = () => {}) => {
  const controller = createSessionController(42, {
    getSession: async () => session(), startSession: async () => session(),
    submitAnswer: async () => session({ turnCount: 2 }), ...api,
  }, changed);
  controllers.push(controller);
  return controller;
};
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };

test('duplicate submission and draft edits are blocked while the same answer is in flight', async () => {
  const result = deferred(); let calls = 0;
  const controller = make({ submitAnswer: () => { calls++; return result.promise; } });
  await controller.open('session-a');
  controller.setDraft('first answer');
  const submitted = controller.submit();
  controller.setDraft('changed while sending');
  await controller.submit();
  assert.equal(calls, 1);
  assert.equal(controller.getSnapshot().draft, 'first answer');
  result.resolve(session({ turnCount: 2 }));
  await submitted;
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getPendingAnswer(42, 'session-a'), null);
});

test('response loss recovers committed server state without another POST', async () => {
  let state = session(); let posts = 0;
  const controller = make({
    getSession: async () => state,
    submitAnswer: async (_, data) => {
      posts++;
      state = session({ turnCount: 2, turns: [{ speaker: 'user', requestId: data.requestId }] });
      throw new ApiError('connection lost', { transportCode: 'ERR_NETWORK' });
    },
  });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  assert.equal(posts, 1);
  assert.equal(controller.getSnapshot().server.turnCount, 2);
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getPendingAnswer(42, 'session-a'), null);
});

test('round twelve validation failure keeps the full draft through reload and retries the original request', async () => {
  const original = '完整回答'.repeat(300);
  const attempts = [];
  const state = session({ turnCount: 12, totalRounds: 12 });
  const first = make({ getSession: async () => state, submitAnswer: async (_, data) => {
    attempts.push(data); throw new ApiError('model validation failed', { status: 502 });
  } });
  await first.open('session-a'); first.setDraft(original); await first.submit();
  assert.equal(first.getSnapshot().draft, original);
  first.dispose();
  const recovered = deferred();
  const second = make({ getSession: async () => state, submitAnswer: async (_, data) => {
    attempts.push(data); return recovered.promise;
  } });
  const reopening = second.open('session-a');
  await flush();
  assert.equal(second.getSnapshot().draft, original);
  assert.deepEqual(attempts[1], attempts[0]);
  recovered.resolve(session({ state: 'FINISHED', turnCount: 12, totalRounds: 12 }));
  await reopening;
  assert.equal(second.getSnapshot().server.state, 'FINISHED');
  assert.equal(second.getSnapshot().draft, '');
});

test('409 refreshes the current question and clears a stale draft without replaying it', async () => {
  let state = session(); let posts = 0;
  const controller = make({
    getSession: async () => state,
    submitAnswer: async () => { posts++; state = session({ turnCount: 2 }); throw new ApiError('conflict', { status: 409 }); },
  });
  await controller.open('session-a'); controller.setDraft('old question answer'); await controller.submit();
  assert.equal(posts, 1);
  assert.equal(controller.getSnapshot().server.turnCount, 2);
  assert.equal(controller.getSnapshot().draft, '');
});

test('429 respects Retry-After for manual submit/recovery and keeps the original request id', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  const attempts = []; let reads = 0;
  const controller = make({
    getSession: async () => { reads++; return session(); },
    submitAnswer: async (_, data) => {
      attempts.push(data);
      if (attempts.length === 1) throw new ApiError('quota', { status: 429, retryAfter: '8' });
      return session({ turnCount: 2 });
    },
  });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  await controller.submit(); await controller.recover();
  assert.equal(attempts.length, 1); assert.equal(reads, 1);
  t.mock.timers.tick(7999); await controller.submit(); assert.equal(attempts.length, 1);
  t.mock.timers.tick(1); await controller.submit();
  assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1]);
});

test('lease polling preserves the draft and replays once with the original id after expiry', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  const answer = pending(); let posts = 0;
  const controller = make({
    getSession: async () => session({ answerLeaseUntil: new Date(102000).toISOString() }),
    submitAnswer: async (_, data) => { posts++; assert.equal(data.requestId, answer.requestId); return session({ turnCount: 2 }); },
  });
  await controller.open('session-a');
  assert.equal(controller.getSnapshot().draft, answer.answer);
  assert.equal(controller.getSnapshot().ui.phase, 'waiting');
  await controller.submit(); assert.equal(posts, 0);
  t.mock.timers.tick(2500); await flush();
  assert.equal(posts, 1); assert.equal(controller.getSnapshot().server.turnCount, 2);
});

test('unmount disposes the polling timer and aborts the current signal', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  pending(); let reads = 0; let signal;
  const controller = make({ getSession: async (_, value) => {
    reads++; signal = value; return session({ answerLeaseUntil: new Date(160000).toISOString() });
  } });
  await controller.open('session-a'); controller.dispose();
  assert.equal(signal.aborted, true);
  t.mock.timers.tick(60000); await flush(); assert.equal(reads, 1);
});

test('a new session cancels the old read and ignores its late error', async () => {
  const old = deferred(); let signal;
  const controller = make({ getSession: (id, value) => {
    if (id === 'session-a') { signal = value; return old.promise; }
    return Promise.resolve(session({ sessionId: id }));
  } });
  const opened = controller.open('session-a'); await controller.open('session-b');
  old.reject(new ApiError('late failure', { status: 500 })); await opened;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().ui.error, '');
});

test('account switching blocks late start and submit effects and clears recovery storage', async () => {
  for (const operation of ['start', 'submit']) {
    const late = deferred(); let signal; let changed = 0;
    const controller = make({
      startSession: (_, value) => { signal = value; return late.promise; },
      submitAnswer: (_, __, value) => { signal = value; return late.promise; },
    }, () => changed++);
    await controller.open('session-a'); controller.setDraft('secret answer');
    const run = operation === 'start' ? controller.start({}) : controller.submit();
    const before = controller.getSnapshot();
    resetAccountScope();
    late.resolve(session({ sessionId: 'late-session', turnCount: 2 })); await run;
    assert.equal(signal.aborted, true);
    assert.equal(controller.getSnapshot(), before);
    assert.equal(changed, 0);
    assert.equal(getActiveSession(42), null);
    assert.equal(getPendingAnswer(42, 'session-a'), null);
  }
});

test('temporary read failures use bounded backoff and leave manual recovery available', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  pending(); let reads = 0;
  const controller = make({ getSession: async () => { reads++; throw new ApiError('unavailable', { status: 503 }); } });
  await controller.open('session-a');
  t.mock.timers.tick(2500); await flush();
  t.mock.timers.tick(5000); await flush();
  t.mock.timers.tick(60000); await flush();
  assert.equal(reads, 3);
  assert.equal(controller.getSnapshot().ui.phase, 'idle');
  assert.equal(controller.getSnapshot().ui.needsRecovery, true);
  assert.ok(getPendingAnswer(42, 'session-a'));
});

test('refresh checks committed answers without replay, and expired drafts never submit', async () => {
  const answer = pending(); let posts = 0;
  const controller = make({
    getSession: async () => session({ turns: [{ speaker: 'user', requestId: answer.requestId }] }),
    submitAnswer: async () => { posts++; return session(); },
  });
  rememberActiveSession(42, 'session-a'); controller.initialize(); await flush();
  assert.equal(posts, 0); assert.equal(getPendingAnswer(42, 'session-a'), null);
  values.set('interview:pending:42:session-a:1', JSON.stringify({ ...answer, expiresAt: Date.now() - 1 }));
  await controller.open('session-a'); assert.equal(posts, 0);
});

test('missing session clears its recovery record and stops polling', async () => {
  pending();
  const controller = make({ getSession: async () => { throw new ApiError('not found', { status: 404 }); } });
  await controller.open('session-a');
  assert.equal(getPendingAnswer(42, 'session-a'), null);
  assert.equal(getActiveSession(42), null);
  assert.equal(controller.getSnapshot().ui.needsRecovery, false);
});

test('same-turn 409 is read once and does not automatically repeat the rejected POST', async () => {
  let posts = 0;
  const controller = make({ submitAnswer: async () => { posts++; throw new ApiError('conflict', { status: 409 }); } });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  assert.equal(posts, 1); assert.equal(controller.getSnapshot().draft, 'answer');
  assert.equal(controller.getSnapshot().ui.phase, 'idle');
});

test('switching sessions aborts an old submit and its late success cannot overwrite the selected session', async () => {
  const old = deferred(); let signal;
  const controller = make({
    getSession: async (id) => session({ sessionId: id }),
    submitAnswer: (_, __, value) => { signal = value; return old.promise; },
  });
  await controller.open('session-a'); controller.setDraft('private answer');
  const submitted = controller.submit();
  await controller.open('session-b');
  old.resolve(session({ turnCount: 2 })); await submitted;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().draft, '');
  // Cancellation is not proof that the server rejected the old answer; retain recovery for that session.
  assert.ok(getPendingAnswer(42, 'session-a'));
});

test('a temporary submit failure is checked once; an explicit retry keeps the same payload', async () => {
  const attempts = [];
  const controller = make({ submitAnswer: async (_, data) => {
    attempts.push(data);
    if (attempts.length === 1) throw new ApiError('temporary failure', { status: 503, requestId: 'trace-temporary' });
    return session({ turnCount: 2 });
  } });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  assert.equal(attempts.length, 1);
  assert.match(controller.getSnapshot().ui.error, /trace-temporary/);
  assert.equal(controller.getSnapshot().ui.needsRecovery, false);
  await controller.submit(); assert.deepEqual(attempts[0], attempts[1]);
});

test('429 while reading honors an HTTP-date before retrying and cannot be bypassed by opening again', async (t) => {
  const now = Date.parse('2026-10-01T00:00:00Z');
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now });
  let reads = 0;
  const controller = make({ getSession: async () => {
    reads++;
    if (reads === 1) throw new ApiError('quota', { status: 429, retryAfter: 'Thu, 01 Oct 2026 00:00:10 GMT' });
    return session();
  } });
  await controller.open('session-a'); await controller.open('session-a');
  assert.equal(reads, 1);
  t.mock.timers.tick(9999); await flush(); assert.equal(reads, 1);
  t.mock.timers.tick(1); await flush(); assert.equal(reads, 2);
  assert.equal(controller.getSnapshot().ui.phase, 'idle');
});

test('deleting a session during recovery aborts the read and cannot resurrect the deleted record', async () => {
  const result = deferred(); let signal;
  pending();
  const controller = make({ getSession: (_, value) => { signal = value; return result.promise; } });
  const opened = controller.open('session-a');
  controller.forgetDeletedSession('session-a');
  result.resolve(session()); await opened;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server, null);
  assert.equal(getPendingAnswer(42, 'session-a'), null);
  assert.equal(getActiveSession(42), null);
});

test('Retry-After on a temporary POST failure also delays the automatic status check', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  let reads = 0, posts = 0;
  const controller = make({
    getSession: async () => { reads++; return session(); },
    submitAnswer: async () => { posts++; throw new ApiError('busy', { status: 503, retryAfter: '4' }); },
  });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  t.mock.timers.tick(3999); await flush(); assert.equal(reads, 1);
  t.mock.timers.tick(1); await flush(); assert.equal(reads, 2); assert.equal(posts, 1);
});
