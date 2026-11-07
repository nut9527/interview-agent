import test, { beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createSessionController } from './sessionController.ts';
import { ApiError } from '../../api/ApiError.ts';
import { resetAccountScope } from '../../api/accountScope.ts';
import { interviewService } from '../../api/modules/interview.ts';
import { interviewRuns } from '../../api/modules/interviewRuns.ts';
import { getPendingAnswer, rememberPendingAnswer, rememberActiveSession, getActiveSession } from './pendingAnswer.ts';

const values = new Map();
globalThis.sessionStorage = {
  get length() { return values.size; }, key: (index) => [...values.keys()][index] ?? null,
  getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
  removeItem: (key) => values.delete(key),
};
const controllers = [];
beforeEach(() => { values.clear(); localStorage.removeItem("interview-run:42"); });
afterEach(() => { controllers.forEach((controller) => controller.dispose()); controllers.length = 0; resetAccountScope(); });
const session = (extra = {}) => ({
  sessionId: 'session-a', state: 'WAITING_ANSWER', turnCount: 1, turns: [], answerLeaseUntil: null,
  ...extra,
});
const pending = () => rememberPendingAnswer(42, {
  sessionId: 'session-a', expectedTurn: 1, answer: 'my original answer', requestId: 'request_001',
});
// Model the command endpoint and its committed session separately.
const make = (api = {}, changed = () => {}) => {
  const committed = new Map();
  const runs = {
    active: async () => [],
    byCommand: async () => { throw new ApiError('missing', { status: 404 }); },
    start: async (data, requestId, signal) => {
      const value = await (api.completeStart?.(data, signal) ?? session());
      committed.set(value.sessionId, value);
      return { runId: requestId, sessionId: value.sessionId, status: 'SUCCEEDED' };
    },
    answer: async (id, data, signal) => {
      const value = await (api.completeAnswer?.(id, data, signal) ?? session({ turnCount: 2 }));
      committed.set(value.sessionId, { ...value, turns: [...value.turns, { speaker: 'user', requestId: data.requestId, content: data.answer }] });
      return { runId: data.requestId, sessionId: value.sessionId, status: 'SUCCEEDED' };
    },
    ...api.runs,
  };
  const controller = createSessionController(42, {
    getSession: async (id, signal) => committed.get(id) ?? (api.getSession?.(id, signal) ?? session()),
    runs,
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

test('corrupt recovery markers are discarded and do not block opening a session', async () => {
  for (const stored of ['', '{', 'null', '[]', '{}', '{"runId":12}', '{"operation":"START"}', '{"runId":"r","sessionId":42}']) {
    localStorage.setItem('interview-run:42', stored);
    let discoveries = 0;
    const controller = make({ runs: { active: async () => { discoveries++; return []; } } });
    await controller.open('session-a');
    assert.equal(controller.getSnapshot().server.sessionId, 'session-a');
    assert.equal(controller.getSnapshot().ui.needsRecovery, false);
    assert.equal(localStorage.getItem('interview-run:42'), null);
    assert.equal(discoveries, 1);
    controller.dispose();
  }
});

test('corrupt local marker still recovers an accepted server run without another submission', async () => {
  localStorage.setItem('interview-run:42', '{');
  let reads = 0;
  const controller = make({ runs: {
    active: async () => [{ runId: 'accepted', sessionId: 'session-a' }],
    get: async id => { reads++; assert.equal(id, 'accepted'); return { runId: id, status: 'SUCCEEDED', sessionId: 'session-a' }; },
    start: async () => assert.fail('recovery must not submit'),
    answer: async () => assert.fail('recovery must not submit'),
  } });
  await controller.open('session-a');
  assert.equal(reads, 1);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-a');
  assert.equal(localStorage.getItem('interview-run:42'), null);
});

test('late active-run discovery cannot recreate recovery state after logout', async () => {
  const discovery = deferred();
  const controller = make({ runs: {
    active: async () => discovery.promise,
    get: async () => assert.fail('old account must not resume a run'),
  } });
  const opened = controller.open('session-a');
  await flush(); resetAccountScope();
  discovery.resolve([{ runId: 'old-private-run', sessionId: 'session-a' }]);
  await opened;
  assert.equal(localStorage.getItem('interview-run:42'), null);
  assert.equal(controller.getSnapshot().server, null);
});

test('late active-run discovery cannot overwrite a newly selected session', async () => {
  const discovery = deferred(); let discoveries = 0;
  const controller = make({
    getSession: async id => session({ sessionId: id }),
    runs: { active: async () => ++discoveries === 1 ? discovery.promise : [] },
  });
  const opened = controller.open('session-a');
  await flush(); await controller.open('session-b');
  discovery.resolve([{ runId: 'old-run', sessionId: 'session-b' }]);
  await opened;
  assert.equal(localStorage.getItem('interview-run:42'), null);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
});

test('duplicate submission and draft edits are blocked while the same answer is in flight', async () => {
  const result = deferred(); let calls = 0;
  const controller = make({ completeAnswer: () => { calls++; return result.promise; } });
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
    completeAnswer: async (_, data) => {
      posts++;
      state = session({ turnCount: 2, turns: [{ speaker: 'user', requestId: data.requestId }] });
      throw new ApiError('connection lost', { transportCode: 'ERR_NETWORK' });
    },
  });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  await controller.recover();
  assert.equal(posts, 1);
  assert.equal(controller.getSnapshot().server.turnCount, 2);
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getPendingAnswer(42, 'session-a'), null);
});

test('round twelve uncertain command keeps the full draft through reload and explicit same-key retry', async () => {
  const original = '完整回答'.repeat(300);
  const attempts = [];
  const state = session({ turnCount: 12, totalRounds: 12 });
  const first = make({ getSession: async () => state, completeAnswer: async (_, data) => {
    attempts.push(data); throw new ApiError('model validation failed', { status: 502 });
  } });
  await first.open('session-a'); first.setDraft(original); await first.submit();
  assert.equal(first.getSnapshot().draft, original);
  first.dispose();
  const recovered = deferred();
  const second = make({ getSession: async () => state, completeAnswer: async (_, data) => {
    attempts.push(data); return recovered.promise;
  } });
  await second.open('session-a');
  assert.equal(second.getSnapshot().draft, original);
  assert.equal(attempts.length, 1);
  const reopening = second.submit();
  await flush();
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
    completeAnswer: async () => { posts++; state = session({ turnCount: 2 }); throw new ApiError('conflict', { status: 409 }); },
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
    completeAnswer: async (_, data) => {
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
    completeAnswer: async (_, data) => { posts++; assert.equal(data.requestId, answer.requestId); return session({ turnCount: 2 }); },
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
  const opened = controller.open('session-a'); await flush(); await controller.open('session-b');
  old.reject(new ApiError('late failure', { status: 500 })); await opened;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().ui.error, '');
});

test('account switching blocks late start and submit effects and clears recovery storage', async () => {
  for (const operation of ['start', 'submit']) {
    const late = deferred(); let signal; let changed = 0;
    const controller = make({
      completeStart: (_, value) => { signal = value; return late.promise; },
      completeAnswer: (_, __, value) => { signal = value; return late.promise; },
    }, () => changed++);
    await controller.open('session-a'); controller.setDraft('secret answer');
    const run = operation === 'start' ? controller.start({}) : controller.submit();
    await flush();
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
    completeAnswer: async () => { posts++; return session(); },
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
  const controller = make({ completeAnswer: async () => { posts++; throw new ApiError('conflict', { status: 409 }); } });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  assert.equal(posts, 1); assert.equal(controller.getSnapshot().draft, 'answer');
  assert.equal(controller.getSnapshot().ui.phase, 'idle');
});

test('switching sessions aborts an old submit and its late success cannot overwrite the selected session', async () => {
  const old = deferred(); let signal;
  const controller = make({
    getSession: async (id) => session({ sessionId: id }),
    completeAnswer: (_, __, value) => { signal = value; return old.promise; },
  });
  await controller.open('session-a'); controller.setDraft('private answer');
  const submitted = controller.submit();
  await controller.open('session-b');
  old.resolve(session({ turnCount: 2 })); await submitted;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().draft, '');
  // 取消不能证明服务端已拒绝旧回答，应保留该会话的恢复信息。
  assert.ok(getPendingAnswer(42, 'session-a'));
});

test('a temporary submit failure is checked once; an explicit retry keeps the same payload', async () => {
  const attempts = [];
  const controller = make({ completeAnswer: async (_, data) => {
    attempts.push(data);
    if (attempts.length === 1) throw new ApiError('temporary failure', { status: 503, requestId: 'trace-temporary' });
    return session({ turnCount: 2 });
  } });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  assert.equal(attempts.length, 1);
  assert.match(controller.getSnapshot().ui.error, /trace-temporary/);
  assert.equal(controller.getSnapshot().ui.needsRecovery, true);
  await controller.recover();
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
  await flush();
  controller.forgetDeletedSession('session-a');
  result.resolve(session()); await opened;
  assert.equal(signal.aborted, true);
  assert.equal(controller.getSnapshot().server, null);
  assert.equal(getPendingAnswer(42, 'session-a'), null);
  assert.equal(getActiveSession(42), null);
});

test('an uncertain command is not automatically resubmitted while waiting for explicit recovery', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100000 });
  let reads = 0, posts = 0;
  const controller = make({
    getSession: async () => { reads++; return session(); },
    completeAnswer: async () => { posts++; throw new ApiError('busy', { status: 503, retryAfter: '4' }); },
  });
  await controller.open('session-a'); controller.setDraft('answer'); await controller.submit();
  await controller.recover(); assert.equal(reads, 1);
  t.mock.timers.tick(3999); await flush(); assert.equal(reads, 1);
  t.mock.timers.tick(1); await flush(); assert.equal(reads, 1); assert.equal(posts, 1);
  await controller.recover(); assert.equal(reads, 2); assert.equal(posts, 1);
});


test('lost answer requests are reconciled against the receipt and current turn before a same-key retry', async () => {
  for (const outcome of ['accepted', 'undelivered', 'turn-advanced']) {
    const posts = [];
    const succeeded = { runId: 'r', status: 'SUCCEEDED', sessionId: 'session-a' };
    const api = {
      runs: {
        answer: async (_, payload) => {
          posts.push(payload);
          if (posts.length === 1) throw new ApiError('lost', { transportCode: 'ERR_NETWORK' });
          return succeeded;
        },
        byCommand: async (operation, key) => {
          assert.equal(operation, 'ANSWER'); assert.equal(key, posts[0].requestId);
          if (outcome === 'accepted') return succeeded;
          throw new ApiError('not found', { status: 404 });
        },
      },
      getSession: async () => {
        if (!posts.length || (outcome === 'undelivered' && posts.length === 1)) return session();
        if (outcome === 'turn-advanced') return session({ turnCount: 2 });
        return session({ turnCount: 2, turns: [{ speaker: 'user', ...posts[0], content: posts[0].answer }] });
      },
    };
    let controller = make(api);
    await controller.open('session-a'); controller.setDraft('durable answer'); await controller.submit();
    if (outcome !== 'accepted') {
      assert.equal(controller.getSnapshot().ui.needsRecovery, true);
      controller.dispose(); controller = make(api); await controller.open('session-a');
      assert.equal(controller.getSnapshot().ui.needsRecovery, false);
      assert.equal(posts.length, 1);
      if (outcome === 'undelivered') {
        assert.equal(controller.getSnapshot().draft, 'durable answer');
        await controller.submit();
        assert.deepEqual(posts[1], posts[0]);
      }
    }
    assert.equal(posts.length, outcome === 'undelivered' ? 2 : 1);
    assert.equal(controller.getSnapshot().draft, '');
    assert.equal(localStorage.getItem('interview-run:42'), null);
    controller.dispose();
  }
});

test('an undelivered start survives refresh and uses its original key on explicit retry', async () => {
  const posts = [];
  const runs = {
    active: async () => [],
    byCommand: async () => { throw new ApiError('missing', { status: 404 }); },
    start: async (_, key) => {
      posts.push(key);
      if (posts.length === 1) throw new ApiError('offline', { transportCode: 'ERR_NETWORK' });
      return { runId: 'run-a', sessionId: 'session-a', status: 'SUCCEEDED' };
    },
  };
  const first = make({ runs });
  await first.start({});
  assert.equal(first.getSnapshot().server, null);
  first.dispose();
  const refreshed = make({ runs });
  await refreshed.recover();
  assert.equal(posts.length, 1);
  assert.equal(refreshed.getSnapshot().ui.needsRecovery, false);
  await refreshed.start({});
  assert.equal(posts.length, 2);
  assert.equal(posts[1], posts[0]);
  assert.equal(refreshed.getSnapshot().server.sessionId, 'session-a');
  assert.equal(localStorage.getItem('interview-run:42'), null);
});

test('retrying a question-bank start recovers the accepted session after a lost response', async (t) => {
  const practiceKey = 'interview:practice-start:test', posts = [];
  const accepted = { runId: 'run-a', sessionId: 'session-a', status: 'SUCCEEDED' };
  t.mock.method(interviewRuns, 'start', async (_, key) => {
    posts.push(key);
    if (posts.length === 1) throw new ApiError('response lost', { transportCode: 'ERR_NETWORK' });
    assert.equal(key, posts[0]);
    return accepted;
  });
  t.mock.method(interviewRuns, 'byCommand', async () => { throw new ApiError('still offline', { status: 503 }); });
  t.mock.method(interviewService, 'getSession', async id => session({ sessionId: id }));
  await assert.rejects(interviewService.startSession({}, undefined, practiceKey), /still offline/);
  assert.equal(sessionStorage.getItem(practiceKey), posts[0]);
  const result = await interviewService.startSession({}, undefined, practiceKey);
  assert.equal(result.sessionId, accepted.sessionId);
  assert.equal(sessionStorage.getItem(practiceKey), null);
});

test('failed command retains draft; deliberate resubmission gets new key', async () => {
  const keys = [];
  const controller = make({ runs: { answer: async (_, data) => { keys.push(data.requestId); return { runId: 'r', status: 'FAILED', errorCode: 'MODEL_OUTPUT_INVALID' }; } } });
  await controller.open('session-a');controller.setDraft('draft retained');await controller.submit();
  assert.equal(controller.getSnapshot().draft, 'draft retained');await controller.submit();
  assert.equal(keys.length, 2);assert.notEqual(keys[0], keys[1]);
});

test('reloading a failed answer restores its session and draft without replaying the failed command', async () => {
  const answer = pending();
  localStorage.setItem('interview-run:42', JSON.stringify({ operation: 'ANSWER', requestId: answer.requestId, sessionId: answer.sessionId }));
  const keys = [];
  const controller = make({ runs: {
    byCommand: async () => ({ runId: 'failed', sessionId: answer.sessionId, expectedTurn: 1, status: 'FAILED', errorCode: 'MODEL_OUTPUT_INVALID' }),
    answer: async (_, data) => { keys.push(data.requestId); return { runId: 'retry', status: 'FAILED' }; },
  } });
  await controller.open(answer.sessionId);
  assert.equal(controller.getSnapshot().server.sessionId, answer.sessionId);
  assert.equal(controller.getSnapshot().draft, answer.answer);
  assert.equal(controller.getSnapshot().ui.needsRecovery, false);
  assert.match(controller.getSnapshot().ui.error, /MODEL_OUTPUT_INVALID/);
  assert.equal(getPendingAnswer(42, answer.sessionId), null);
  assert.equal(localStorage.getItem('interview-run:42'), null);
  assert.deepEqual(keys, []);
  await controller.submit();
  assert.equal(keys.length, 1);
  assert.notEqual(keys[0], answer.requestId);
});

test('recovered successful answers still require their own receipt before discarding the draft', async () => {
  const answer = pending();
  localStorage.setItem('interview-run:42', JSON.stringify({ runId: 'accepted', sessionId: answer.sessionId }));
  let committed = false;
  const controller = make({
    getSession: async () => session({ turnCount: 2, turns: committed ? [{ speaker: 'user', requestId: answer.requestId }] : [] }),
    runs: { get: async () => ({ runId: 'accepted', sessionId: answer.sessionId, expectedTurn: 1, status: 'SUCCEEDED' }) },
  });
  await controller.open(answer.sessionId);
  assert.equal(controller.getSnapshot().draft, answer.answer);
  assert.equal(controller.getSnapshot().ui.needsRecovery, true);
  assert.match(controller.getSnapshot().ui.error, /回执/);
  assert.ok(getPendingAnswer(42, answer.sessionId));
  committed = true;
  await controller.recover();
  assert.equal(controller.getSnapshot().server.turnCount, 2);
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getPendingAnswer(42, answer.sessionId), null);
});

test('late recovery context cannot overwrite a newly selected session', async () => {
  const answer = pending(), oldSession = deferred();
  localStorage.setItem('interview-run:42', JSON.stringify({ runId: 'accepted', sessionId: answer.sessionId }));
  const controller = make({
    getSession: async id => id === answer.sessionId ? oldSession.promise : session({ sessionId: id }),
    runs: { get: async () => ({ runId: 'accepted', sessionId: answer.sessionId, expectedTurn: 1, status: 'FAILED' }) },
  });
  const opened = controller.open(answer.sessionId);
  await flush();
  await controller.open('session-b');
  oldSession.resolve(session());
  await opened;
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getActiveSession(42), 'session-b');
  assert.ok(getPendingAnswer(42, answer.sessionId));
});

test('opening a revoked run after reload restores the draft but blocks another submission', async () => {
  const answer = pending();
  const controller = make({ runs: {
    get: async () => ({ runId: 'revoked', sessionId: answer.sessionId, expectedTurn: 1, status: 'FAILED', errorCode: 'RUN_SOURCE_REVOKED' }),
    answer: async () => assert.fail('revoked sources must not submit'),
  } });
  await controller.openRun('revoked');
  assert.equal(controller.getSnapshot().ui.activeId, answer.sessionId);
  assert.equal(controller.getSnapshot().server.sourceRevoked, true);
  assert.equal(controller.getSnapshot().server.agent, null);
  assert.equal(controller.getSnapshot().draft, answer.answer);
  await controller.submit();
});

test('an old failed, cancelled or expired run cannot put an earlier answer into the next question', async () => {
  for (const status of ['FAILED', 'CANCELLED', 'EXPIRED']) {
    const answer = pending();
    localStorage.setItem('interview-run:42', JSON.stringify({ runId: 'old', sessionId: answer.sessionId }));
    const controller = make({
      getSession: async () => session({ turnCount: 2 }),
      runs: {
        get: async () => ({ runId: 'old', sessionId: answer.sessionId, expectedTurn: 1, status }),
        answer: async () => assert.fail('old answer must not submit to the next question'),
      },
    });
    await controller.open(answer.sessionId);
    assert.equal(controller.getSnapshot().server.turnCount, 2);
    assert.equal(controller.getSnapshot().draft, '');
    assert.equal(getPendingAnswer(42, answer.sessionId), null);
    assert.equal(controller.getSnapshot().ui.needsRecovery, false);
    assert.equal(controller.getSnapshot().ui.error, '');
    assert.equal(localStorage.getItem('interview-run:42'), null);
    await controller.submit();
    controller.dispose();
  }
});

test('manual run recovery refreshes a cached session before restoring an obsolete draft', async () => {
  const answer = pending();
  let reads = 0;
  const controller = make({
    getSession: async () => ++reads === 1 ? session() : session({ turnCount: 2 }),
    runs: {
      answer: async () => { throw new ApiError('lost', { transportCode: 'ERR_NETWORK' }); },
      byCommand: async () => { throw new ApiError('unavailable', { status: 503 }); },
      get: async () => ({ runId: 'old', sessionId: answer.sessionId, expectedTurn: 1, status: 'FAILED' }),
    },
  });
  // 先显示第1轮，再模拟另一标签推进会话；本地服务端快照仍为旧轮次。
  await controller.open(answer.sessionId);
  assert.equal(controller.getSnapshot().server.turnCount, 1);
  localStorage.setItem('interview-run:42', JSON.stringify({ runId: 'old', sessionId: answer.sessionId }));
  await controller.recover();
  assert.equal(reads, 2);
  assert.equal(controller.getSnapshot().server.turnCount, 2);
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(getPendingAnswer(42, answer.sessionId), null);
});

test('a run-only recovery marker cannot replace another explicitly selected session', async () => {
  const answer = pending();
  localStorage.setItem('interview-run:42', JSON.stringify({ runId: 'other' }));
  const reads = [];
  const controller = make({
    getSession: async id => { reads.push(id); return session({ sessionId: id }); },
    runs: { get: async () => ({ runId: 'other', sessionId: answer.sessionId, expectedTurn: 1, status: 'FAILED' }) },
  });
  await controller.open('session-b');
  assert.deepEqual(reads, ['session-b']);
  assert.equal(controller.getSnapshot().server.sessionId, 'session-b');
  assert.equal(controller.getSnapshot().draft, '');
  assert.equal(controller.getSnapshot().ui.needsRecovery, false);
  assert.ok(getPendingAnswer(42, answer.sessionId));
  assert.ok(localStorage.getItem('interview-run:42'));
});

test('queue timeout retains answer and offers deliberate resubmission without advancing turn', async () => {
  let posts = 0;
  const controller = make({ runs: { answer: async () => {
    posts++; return { runId: 'queued', status: 'EXPIRED', errorCode: 'RUN_QUEUE_TIMEOUT' };
  } } });
  await controller.open('session-a'); controller.setDraft('keep queued answer'); await controller.submit();
  assert.equal(posts, 1);
  assert.equal(controller.getSnapshot().draft, 'keep queued answer');
  assert.equal(controller.getSnapshot().server.turnCount, 1);
  assert.match(controller.getSnapshot().ui.error, /排队超时/);
  assert.equal(localStorage.getItem('interview-run:42'), null);
});

test('leaving page stops subscription without cancelling accepted run', async () => {
  const waiting = deferred();let cancelCalls = 0, observedSignal;
  const controller = make({ runs: {
    answer: async () => ({ runId: 'r', status: 'RUNNING' }),
    stream: async (_, __, signal) => { observedSignal = signal;return waiting.promise; },
    cancel: async () => {cancelCalls++;},
  } });
  await controller.open('session-a');controller.setDraft('answer');const submitted = controller.submit();await flush();
  controller.dispose();assert.equal(observedSignal.aborted, true);waiting.reject(new DOMException('abort','AbortError'));await submitted;
  assert.equal(cancelCalls, 0);
});

test('source revocation withdraws cached agent, retains draft and blocks a new paid submission even if refresh fails', async () => {
  let posts = 0, reads = 0;
  const controller = make({
    getSession: async () => { if (++reads > 1) throw new Error('refresh lost'); return session({ agent: { training: { knowledge: [{ content: 'private source' }] } } }); },
    runs: { answer: async () => { posts++; return { runId: 'revoked', sessionId: 'session-a', status: 'FAILED', errorCode: 'RUN_SOURCE_REVOKED' }; } },
  });
  await controller.open('session-a'); controller.setDraft('keep this answer'); await controller.submit();
  assert.equal(controller.getSnapshot().server.sourceRevoked, true);
  assert.equal(controller.getSnapshot().server.agent, null);
  assert.equal(controller.getSnapshot().draft, 'keep this answer');
  assert.match(controller.getSnapshot().ui.error, /资料已删除/);
  await controller.submit(); assert.equal(posts, 1);
});

test('memory revocation retains the current session and draft and a deliberate retry gets a new command', async () => {
  const keys = [];
  const controller = make({ runs: { answer: async (_, payload) => {
    keys.push(payload.requestId); return { runId: 'memory-withdrawn', status: 'FAILED', errorCode: 'RUN_MEMORY_REVOKED' };
  } } });
  await controller.open('session-a'); controller.setDraft('keep this answer'); await controller.submit();
  assert.equal(controller.getSnapshot().server.sourceRevoked, undefined);
  assert.equal(controller.getSnapshot().draft, 'keep this answer');
  assert.match(controller.getSnapshot().ui.error, /历史观察已纠正/);
  await controller.submit(); assert.equal(keys.length, 2); assert.notEqual(keys[0], keys[1]);
});

test('durable progress ignores replayed events and cannot cross session/account scope', async () => {
  let callback;
  const waiting = deferred();
  const controller = make({ runs: {
    answer: async () => ({ runId: 'r', status: 'RUNNING' }),
    stream: async (_, __, signal, onEvent) => { callback = onEvent; return waiting.promise; },
  } });
  await controller.open('session-a'); controller.setDraft('answer'); const submitted = controller.submit(); await flush();
  const event = { runId: 'r', seq: 8, type: 'model.started', message: '正在生成' };
  callback(event); callback({ ...event, message: '重复事件' });
  callback({ ...event, seq: 7, message: '过期事件' });
  assert.equal(controller.getSnapshot().ui.runPhase, '正在生成');
  assert.equal(controller.getSnapshot().ui.lastEventSeq, 8);
  callback({ ...event, runId: 'other', seq: 9, message: '其他运行' });
  assert.equal(controller.getSnapshot().ui.runPhase, '正在生成');
  assert.equal(controller.getSnapshot().ui.lastEventSeq, 8);
  await controller.open('session-b'); callback({ ...event, seq: 9, message: '迟到事件' });
  assert.equal(controller.getSnapshot().ui.runPhase, undefined);
  resetAccountScope(); callback({ ...event, seq: 10 });
  assert.equal(controller.getSnapshot().ui.runPhase, undefined);
  waiting.reject(new DOMException('abort', 'AbortError')); await submitted;
});
