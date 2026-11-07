import type { InterviewRun, interviewRuns } from '../../api/modules/interviewRuns';
import type { InterviewSession, InterviewSessionRequest } from '@/types/api.d';
import { captureAccountScope } from '../../api/accountScope.ts';
import { acceptRunCommand } from '../../api/modules/runCommand.ts';
import { describeApiError, isRequestCanceled, toApiError } from '../../api/ApiError.ts';
import {
  forgetPendingAnswer,
  getActiveSession,
  getPendingAnswer,
  pendingResult,
  pruneExpiredPendingAnswers,
  rememberActiveSession,
  rememberPendingAnswer,
  type PendingAnswer,
} from './pendingAnswer.ts';

interface SessionApi {
  runs: typeof interviewRuns;
  getSession: (id: string, signal?: AbortSignal) => Promise<InterviewSession>;
}

export interface SessionView {
  server: InterviewSession | null;
  draft: string;
  ui: {
    phase: 'idle' | 'starting' | 'submitting' | 'recovering' | 'waiting';
    error: string;
    needsRecovery: boolean;
    retryAt: number;
    activeId: string | null;
    activeRunId?: string;
    lastEventSeq?: number;
    runPhase?: string;
  };
}

/** 管理单页面的请求、定时器与账号作用域，React 只订阅状态快照。 */
export const createSessionController = (userId: number, api: SessionApi, onChanged: () => void) => {
  let view: SessionView = {
    server: null,
    draft: '',
    ui: { phase: 'idle', error: '', needsRecovery: false, retryAt: 0, activeId: null },
  };
  const listeners = new Set<() => void>();
  const account = captureAccountScope();
  let request = new AbortController();
  let disposed = false;
  let pending: PendingAnswer | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | undefined;
  let cooldownTimer: ReturnType<typeof setTimeout> | undefined;
  const emit = (change: Partial<SessionView>, ui: Partial<SessionView['ui']> = {}) => {
    if (disposed || !account.isCurrent()) return;
    view = { ...view, ...change, ui: { ...view.ui, ...ui } };
    listeners.forEach((listener) => listener());
  };
  const current = (signal: AbortSignal) =>
    !disposed && account.isCurrent() && signal === request.signal && !signal.aborted;
  const cancel = () => {
    emit({}, { activeRunId: undefined, runPhase: undefined });
    request.abort();
    clearTimeout(pollTimer);
    clearTimeout(cooldownTimer);
    request = new AbortController();
  };
  const dispose = () => {
    disposed = true;
    cancel();
    account.signal.removeEventListener('abort', dispose);
    listeners.clear();
  };
  account.signal.addEventListener('abort', dispose, { once: true });
  const discardPending = () => {
    if (pending) forgetPendingAnswer(userId, pending);
    pending = null;
  };
  const schedule = (run: () => void, delay: number) => {
    clearTimeout(pollTimer);
    // 浏览器定时器约 24 天后会溢出，较长的 Retry-After 按截止时间分段等待。
    const deadline = Date.now() + delay;
    const tick = () => {
      if (disposed || !account.isCurrent()) return;
      const remaining = deadline - Date.now();
      if (remaining > 0) pollTimer = setTimeout(tick, Math.min(remaining, 2_147_483_647));
      else run();
    };
    tick();
  };
  const coolDown = (delay: number) => {
    const retryAt = Date.now() + delay;
    emit({}, { retryAt });
    clearTimeout(cooldownTimer);
    const tick = () => {
      if (disposed || !account.isCurrent()) return;
      const remaining = retryAt - Date.now();
      if (remaining > 0) cooldownTimer = setTimeout(tick, Math.min(remaining, 2_147_483_647));
      else emit({}, { retryAt: 0 });
    };
    tick();
  };
  const settle = (session: InterviewSession) => {
    localStorage.removeItem(`interview-run:${userId}`);
    discardPending();
    emit(
      { server: session, draft: '' },
      {
        phase: 'idle',
        error: '',
        needsRecovery: false,
        activeRunId: undefined,
        runPhase: undefined,
      },
    );
    onChanged();
  };

  const delay = (ms: number, signal: AbortSignal) =>
    new Promise<void>((resolve, reject) => {
      const abort = () => {
        clearTimeout(timer);
        reject(new DOMException('Aborted', 'AbortError'));
      };
      const timer = setTimeout(() => {
        signal.removeEventListener('abort', abort);
        resolve();
      }, ms);
      if (signal.aborted) abort();
      else signal.addEventListener('abort', abort, { once: true });
    });
  const followRun = async (initial: InterviewRun, signal: AbortSignal): Promise<InterviewSession> => {
    if (!current(signal)) throw new DOMException('Aborted', 'AbortError');
    const runs = api.runs;
    let run = initial,
      seq = 0,
      failures = 0;
    emit({}, { activeRunId: run.runId, lastEventSeq: 0, runPhase: '请求已受理' });
    while (current(signal)) {
      if (run.status === 'SUCCEEDED' && run.sessionId) {
        const session = await api.getSession(run.sessionId, signal);
        if (pending && pendingResult(session, pending) !== 'committed')
          throw new Error('运行已完成，请核对本轮回答回执。');
        return session;
      }
      if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(run.status)) {
        localStorage.removeItem(`interview-run:${userId}`);
        // 失败命令不自动重启，用户重新提交时分配新的幂等键。
        discardPending();
        emit({}, { activeRunId: undefined, runPhase: undefined });
        if (run.errorCode === 'RUN_SOURCE_REVOKED' && view.server) {
          // 立即撤回缓存资料，后续查询只更新安全投影，不重新提交回答。
          emit({ server: { ...view.server, sourceRevoked: true, agent: null } });
          try {
            const latest = await api.getSession(view.server.sessionId, signal);
            if (current(signal) && latest.sourceRevoked) emit({ server: latest });
          } catch {
            /* 刷新失败时保留撤销标记和原回答草稿。 */
          }
        }
        throw new Error(
          run.status === 'CANCELLED'
            ? '运行已取消，回答草稿已保留。'
            : run.errorCode === 'RUN_SOURCE_REVOKED'
              ? '本场来源已失效（资料已删除，或题目已下架、修订），本轮未提交，回答草稿已保留。请刷新来源后新建训练。'
              : run.errorCode === 'RUN_MEMORY_REVOKED'
                ? '历史观察已纠正、删除或来源已撤销，本轮未提交，回答草稿已保留。请核对学习观察后重新提交。'
                : run.errorCode === 'RUN_QUEUE_TIMEOUT'
                  ? '任务排队超时，本轮未提交，回答草稿已保留。请稍后重新提交。'
                  : `本轮未完成（${run.errorCode ?? run.status}），可检查后重新提交。`,
        );
      }
      if (failures < 3) {
        try {
          await runs.stream(run.runId, seq, signal, (event) => {
            if (!current(signal) || event.runId !== run.runId) return;
            if (event.seq <= seq) return;
            seq = event.seq;
            emit({}, { runPhase: event.message, lastEventSeq: seq });
          });
        } catch (error) {
          if (!current(signal)) throw error;
          failures++;
        }
      }
      await delay(Math.min(5000, 500 * 2 ** failures), signal);
      run = await runs.get(run.runId, signal);
    }
    throw new DOMException('Aborted', 'AbortError');
  };
  const command = async (
    operation: 'START' | 'ANSWER',
    requestId: string,
    signal: AbortSignal,
    accept: () => Promise<InterviewRun>,
  ): Promise<InterviewSession> => {
    localStorage.setItem(
      `interview-run:${userId}`,
      JSON.stringify({ operation, requestId, sessionId: view.ui.activeId }),
    );
    const run = await acceptRunCommand(
      accept,
      () => {
        signal.throwIfAborted();
        return api.runs.byCommand(operation, requestId, signal);
      },
      () => {
        if (current(signal)) localStorage.removeItem(`interview-run:${userId}`);
      },
    );
    return followRun(run, signal);
  };
  const recoverRunOnce = async (
    signal: AbortSignal,
    onMissingStart?: (requestId: string) => void,
  ): Promise<boolean> => {
    if (!current(signal)) return true;
    let saved: { operation: string; requestId: string; sessionId?: string; runId?: string } | null = null;
    const stored = localStorage.getItem(`interview-run:${userId}`);
    if (stored !== null) {
      try {
        const value = JSON.parse(stored);
        const hasText = (text: unknown) => typeof text === 'string' && text.trim().length > 0;
        if (
          !value ||
          typeof value !== 'object' ||
          Array.isArray(value) ||
          (value.sessionId != null && !hasText(value.sessionId)) ||
          (value.runId != null && !hasText(value.runId)) ||
          !(hasText(value.runId) || (['START', 'ANSWER'].includes(value.operation) && hasText(value.requestId)))
        ) {
          throw new Error('Invalid saved run');
        }
        saved = value;
      } catch {
        // 本地恢复标记损坏不应锁死页面；仍通过服务端查询发现已受理的任务。
        localStorage.removeItem(`interview-run:${userId}`);
      }
    }
    if (!saved) {
      const active = await api.runs.active(signal);
      // 请求可能晚于页面切换或退出返回，不能重新写入旧账号的恢复标记。
      if (!current(signal)) return true;
      const candidate = active.find((r) => !view.ui.activeId || r.sessionId === view.ui.activeId);
      if (!candidate) return false;
      localStorage.setItem(
        `interview-run:${userId}`,
        JSON.stringify({ runId: candidate.runId, sessionId: candidate.sessionId }),
      );
      return recoverRun(signal);
    }
    if (view.ui.activeId && saved.sessionId && saved.sessionId !== view.ui.activeId) return false;
    emit({}, { phase: 'recovering', needsRecovery: true });
    try {
      let run: InterviewRun;
      try {
        run = saved.runId
          ? await api.runs.get(saved.runId, signal)
          : await api.runs.byCommand(saved.operation, saved.requestId, signal);
      } catch (error) {
        if (!current(signal)) return true;
        if (toApiError(error).status !== 404 || saved.runId) throw error;
        if (saved.operation === 'ANSWER' && saved.sessionId) {
          // 命令可能迟到。保留回答及原键，先核对服务端回执、轮次与租约。
          const marker = `interview-run:${userId}`;
          if (localStorage.getItem(marker) === stored) localStorage.removeItem(marker);
          rememberActiveSession(userId, saved.sessionId);
          emit({}, { activeId: saved.sessionId, error: '尚未查到本轮回答的提交记录。' });
          await reconcile(saved.sessionId, false);
          return true;
        }
        if (saved.operation !== 'START') throw error;
        // 跨刷新保留原键。即使首次 POST 迟到，显式重试也只会建立一个任务。
        onMissingStart?.(saved.requestId);
        emit({}, { phase: 'idle', needsRecovery: false, error: '尚未查到上次开场请求，可使用原设置重新开始。' });
        return false;
      }
      if (!current(signal)) return true;
      // 仅保存 runId 的标记也要按服务端会话归属过滤，不能抢占用户刚打开的另一场面试。
      if (saved.runId && view.ui.activeId && run.sessionId && run.sessionId !== view.ui.activeId) return false;
      if (run.sessionId) {
        // 刷新后先恢复回答上下文，失败终态才能保留草稿，成功终态才能核对提交回执。
        const storedAnswer = getPendingAnswer(userId, run.sessionId);
        if (storedAnswer && storedAnswer.expectedTurn === run.expectedTurn) {
          pending = storedAnswer;
          emit({ draft: storedAnswer.answer });
        }
        if (run.status !== 'SUCCEEDED') {
          const session = await api.getSession(run.sessionId, signal);
          if (!current(signal)) return true;
          rememberActiveSession(userId, session.sessionId);
          emit({ server: session }, { activeId: session.sessionId });
          // 另一标签可能已完成这轮；旧回答不能进入新问题的输入框。
          const result = pending ? pendingResult(session, pending) : null;
          if (result === 'committed' || result === 'stale') {
            settle(session);
            return true;
          }
        }
      }
      const result = await followRun(run, signal);
      if (current(signal)) {
        rememberActiveSession(userId, result.sessionId);
        emit({}, { activeId: result.sessionId });
        settle(result);
      }
    } catch (error) {
      if (!current(signal)) return true;
      emit(
        {},
        {
          phase: 'idle',
          error: describeApiError(error),
          needsRecovery: !!localStorage.getItem(`interview-run:${userId}`),
        },
      );
    }
    return true;
  };
  const recoverRun = async (signal: AbortSignal, onMissingStart?: (requestId: string) => void): Promise<boolean> => {
    try {
      return await recoverRunOnce(signal, onMissingStart);
    } catch (error) {
      if (current(signal)) emit({}, { phase: 'idle', error: describeApiError(error), needsRecovery: true });
      return true;
    }
  };

  const send = async (answer: PendingAnswer, signal: AbortSignal) => {
    emit({}, { phase: 'submitting', error: '', needsRecovery: false });
    try {
      const payload = {
        answer: answer.answer,
        requestId: answer.requestId,
        expectedTurn: answer.expectedTurn,
      };
      const result = await command('ANSWER', answer.requestId, signal, () =>
        api.runs.answer(answer.sessionId, payload, signal),
      );
      if (current(signal)) settle(result);
    } catch (error) {
      if (!current(signal) || isRequestCanceled(error)) return;
      const failure = toApiError(error);
      emit({}, { phase: 'idle', error: describeApiError(error) });
      if (failure.status === 429) {
        coolDown(failure.retryAfterMs ?? 5000);
      } else if (localStorage.getItem(`interview-run:${userId}`)) {
        emit({}, { needsRecovery: true });
        if (failure.retryAfterMs) coolDown(failure.retryAfterMs);
      } else if (failure.status === 409 || failure.transient) {
        // 提交结果不明时只自动重试读取，保留原回答供核对。
        emit({}, { needsRecovery: true });
        if (failure.retryAfterMs !== undefined && failure.retryAfterMs > 0) {
          coolDown(failure.retryAfterMs);
          emit({}, { phase: 'waiting' });
          schedule(() => {
            void reconcile(answer.sessionId, false);
          }, failure.retryAfterMs);
        } else {
          await reconcile(answer.sessionId, false);
        }
      } else {
        discardPending();
      }
    }
  };

  const reconcile = async (id: string, replay: boolean, attempt = 0): Promise<void> => {
    const signal = request.signal;
    if (!current(signal)) return;
    if (await recoverRun(signal)) return;
    emit({}, { phase: 'recovering', needsRecovery: true });
    try {
      const latest = await api.getSession(id, signal);
      if (!current(signal)) return;
      if (!pending || pending.sessionId !== id) pending = getPendingAnswer(userId, id);
      if (pending && pending.expiresAt <= Date.now()) discardPending();
      emit({ server: latest });
      const result = pending ? pendingResult(latest, pending) : null;
      if (result === 'committed' || result === 'stale') {
        settle(latest);
        return;
      }
      if (pending) emit({ draft: pending.answer });
      if (latest.answerLeaseUntil && Date.parse(latest.answerLeaseUntil) > Date.now()) {
        emit({}, { phase: 'waiting', error: replay ? '' : view.ui.error });
        schedule(() => {
          void reconcile(id, replay);
        }, 2500);
        return;
      }
      emit(
        {},
        {
          phase: 'idle',
          needsRecovery: false,
          error: pending && !replay ? `${view.ui.error} 会话已核对，可重试原回答。`.trim() : '',
        },
      );
      if (pending && replay) await send(pending, signal);
    } catch (error) {
      if (!current(signal) || isRequestCanceled(error)) return;
      const failure = toApiError(error);
      if (failure.status === 404 || failure.status === 403) {
        pending ??= getPendingAnswer(userId, id);
        discardPending();
        rememberActiveSession(userId, null);
        emit(
          { server: null, draft: '' },
          {
            phase: 'idle',
            activeId: null,
            needsRecovery: false,
            error: describeApiError(error),
          },
        );
        return;
      }
      emit({}, { phase: 'idle', error: describeApiError(error), needsRecovery: true });
      if (failure.transient && attempt < 2) {
        const delay = failure.retryAfterMs ?? 2500 * 2 ** attempt;
        coolDown(delay);
        emit({}, { phase: 'waiting' });
        schedule(() => {
          void reconcile(id, replay, attempt + 1);
        }, delay);
      } else if (failure.status === 429) {
        coolDown(failure.retryAfterMs ?? 5000);
      }
    }
  };

  const open = async (id: string) => {
    if (disposed || !account.isCurrent() || view.ui.retryAt > Date.now()) return;
    cancel();
    pending = null;
    rememberActiveSession(userId, id);
    emit({ server: null, draft: '' }, { activeId: id, error: '', retryAt: 0 });
    await reconcile(id, true);
  };

  const restart = () => {
    if (disposed || !account.isCurrent()) return;
    cancel();
    pending = null;
    rememberActiveSession(userId, null);
    emit(
      { server: null, draft: '' },
      {
        activeId: null,
        phase: 'idle',
        error: '',
        needsRecovery: false,
        retryAt: 0,
      },
    );
  };

  return {
    getSnapshot: () => view,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    dispose,
    initialize: () => {
      pruneExpiredPendingAnswers();
      const id = getActiveSession(userId);
      if (id) void open(id);
      else void recoverRun(request.signal);
    },
    open,
    recover: async () => {
      if (view.ui.phase !== 'idle' || view.ui.retryAt > Date.now()) return;
      if (await recoverRun(request.signal)) return;
      if (!view.ui.activeId) return;
      await reconcile(view.ui.activeId, true);
    },
    setDraft: (draft: string) => {
      if (view.ui.phase === 'idle' && !view.ui.needsRecovery) emit({ draft });
    },
    start: async (data: InterviewSessionRequest) => {
      if (!current(request.signal) || view.ui.phase !== 'idle' || view.ui.retryAt > Date.now()) return;
      cancel();
      const signal = request.signal;
      let retryStartId: string | undefined;
      if (
        await recoverRun(signal, (key) => {
          retryStartId = key;
        })
      )
        return;
      if (!current(signal)) return;
      emit({}, { phase: 'starting', error: '' });
      try {
        const requestId = retryStartId ?? crypto.randomUUID();
        const session = await command('START', requestId, signal, () => api.runs.start(data, requestId, signal));
        if (!current(signal)) return;
        pending = null;
        rememberActiveSession(userId, session.sessionId);
        emit({}, { activeId: session.sessionId });
        settle(session);
        return session;
      } catch (error) {
        if (!current(signal) || isRequestCanceled(error)) return;
        emit({}, { phase: 'idle', error: describeApiError(error) });
        const failure = toApiError(error);
        if (failure.status === 429) coolDown(failure.retryAfterMs ?? 5000);
      }
    },
    submit: async () => {
      const session = view.server;
      const answer = view.draft.trim();
      if (
        !current(request.signal) ||
        !session ||
        !answer ||
        session.state === 'FINISHED' ||
        session.sourceRevoked ||
        view.ui.phase !== 'idle' ||
        view.ui.needsRecovery ||
        view.ui.retryAt > Date.now()
      )
        return;
      if (
        !pending ||
        pending.expiresAt <= Date.now() ||
        pending.sessionId !== session.sessionId ||
        pending.expectedTurn !== session.turnCount ||
        pending.answer !== answer
      ) {
        pending = rememberPendingAnswer(userId, {
          sessionId: session.sessionId,
          expectedTurn: session.turnCount,
          answer,
          requestId:
            globalThis.crypto?.randomUUID?.() ??
            `answer-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`,
        });
      }
      await send(pending, request.signal);
    },
    forgetDeletedSession: (id: string) => {
      if (disposed || !account.isCurrent()) return;
      const stored = getPendingAnswer(userId, id);
      if (stored) forgetPendingAnswer(userId, stored);
      if (view.ui.activeId === id) restart();
    },
    openRun: async (id: string) => {
      if (disposed || !account.isCurrent()) return;
      cancel();
      pending = null;
      emit({}, { activeId: null });
      localStorage.setItem(`interview-run:${userId}`, JSON.stringify({ runId: id }));
      await recoverRun(request.signal);
    },
    cancelRun: async () => {
      if (!view.ui.activeRunId) return;
      try {
        await api.runs.cancel(view.ui.activeRunId, request.signal);
      } catch (error) {
        if (current(request.signal)) emit({}, { error: describeApiError(error) });
      }
    },
    restart,
  };
};
