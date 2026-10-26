import type { InterviewSession, InterviewSessionRequest, SubmitAnswerRequest } from '@/types/api.d';
import { captureAccountScope } from '../../api/accountScope.ts';
import { describeApiError, isRequestCanceled, toApiError } from '../../api/ApiError.ts';
import {
  forgetPendingAnswer, getActiveSession, getPendingAnswer, pendingResult,
  pruneExpiredPendingAnswers, rememberActiveSession, rememberPendingAnswer,
  type PendingAnswer,
} from './pendingAnswer.ts';

interface SessionApi {
  getSession: (id: string, signal?: AbortSignal) => Promise<InterviewSession>;
  startSession: (data: InterviewSessionRequest, signal?: AbortSignal) => Promise<InterviewSession>;
  submitAnswer: (id: string, data: SubmitAnswerRequest, signal?: AbortSignal) => Promise<InterviewSession>;
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
  };
}

// Owns one page's session requests and timers. React only subscribes to snapshots.
export const createSessionController = (userId: number, api: SessionApi, onChanged: () => void) => {
  let view: SessionView = {
    server: null, draft: '',
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
  const current = (signal: AbortSignal) => !disposed && account.isCurrent()
    && signal === request.signal && !signal.aborted;
  const cancel = () => {
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
    // Browser timers overflow after ~24 days; keep a deadline for long Retry-After values.
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
    discardPending();
    emit({ server: session, draft: '' }, { phase: 'idle', error: '', needsRecovery: false });
    onChanged();
  };

  const send = async (answer: PendingAnswer, signal: AbortSignal) => {
    emit({}, { phase: 'submitting', error: '', needsRecovery: false });
    try {
      const result = await api.submitAnswer(answer.sessionId, {
        answer: answer.answer, requestId: answer.requestId, expectedTurn: answer.expectedTurn,
      }, signal);
      if (current(signal)) settle(result);
    } catch (error) {
      if (!current(signal) || isRequestCanceled(error)) return;
      const failure = toApiError(error);
      emit({}, { phase: 'idle', error: describeApiError(error) });
      if (failure.status === 429) {
        coolDown(failure.retryAfterMs ?? 5000);
      } else if (failure.status === 409 || failure.transient) {
        // Only reads are automatically retried after an ambiguous submission.
        emit({}, { needsRecovery: true });
        if (failure.retryAfterMs !== undefined && failure.retryAfterMs > 0) {
          coolDown(failure.retryAfterMs);
          emit({}, { phase: 'waiting' });
          schedule(() => { void reconcile(answer.sessionId, false); }, failure.retryAfterMs);
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
        schedule(() => { void reconcile(id, replay); }, 2500);
        return;
      }
      emit({}, {
        phase: 'idle', needsRecovery: false,
        error: pending && !replay ? `${view.ui.error} 会话已核对，可重试原回答。`.trim() : '',
      });
      if (pending && replay) await send(pending, signal);
    } catch (error) {
      if (!current(signal) || isRequestCanceled(error)) return;
      const failure = toApiError(error);
      if (failure.status === 404 || failure.status === 403) {
        pending ??= getPendingAnswer(userId, id);
        discardPending();
        rememberActiveSession(userId, null);
        emit({ server: null, draft: '' }, {
          phase: 'idle', activeId: null, needsRecovery: false, error: describeApiError(error),
        });
        return;
      }
      emit({}, { phase: 'idle', error: describeApiError(error), needsRecovery: true });
      if (failure.transient && attempt < 2) {
        const delay = failure.retryAfterMs ?? 2500 * 2 ** attempt;
        coolDown(delay);
        emit({}, { phase: 'waiting' });
        schedule(() => { void reconcile(id, replay, attempt + 1); }, delay);
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
    emit({ server: null, draft: '' }, {
      activeId: null, phase: 'idle', error: '', needsRecovery: false, retryAt: 0,
    });
  };

  return {
    getSnapshot: () => view,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    dispose,
    initialize: () => {
      pruneExpiredPendingAnswers();
      const id = getActiveSession(userId);
      if (id) void open(id);
    },
    open,
    recover: async () => {
      if (view.ui.phase !== 'idle' || view.ui.retryAt > Date.now() || !view.ui.activeId) return;
      await reconcile(view.ui.activeId, true);
    },
    setDraft: (draft: string) => {
      if (view.ui.phase === 'idle' && !view.ui.needsRecovery) emit({ draft });
    },
    start: async (data: InterviewSessionRequest) => {
      if (!current(request.signal) || view.ui.phase !== 'idle' || view.ui.retryAt > Date.now()) return;
      cancel();
      const signal = request.signal;
      emit({}, { phase: 'starting', error: '' });
      try {
        const session = await api.startSession(data, signal);
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
      if (!current(request.signal) || !session || !answer || session.state === 'FINISHED'
          || view.ui.phase !== 'idle' || view.ui.needsRecovery || view.ui.retryAt > Date.now()) return;
      if (!pending || pending.expiresAt <= Date.now() || pending.sessionId !== session.sessionId
          || pending.expectedTurn !== session.turnCount || pending.answer !== answer) {
        pending = rememberPendingAnswer(userId, {
          sessionId: session.sessionId, expectedTurn: session.turnCount, answer,
          requestId: globalThis.crypto?.randomUUID?.()
            ?? `answer-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`,
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
    restart,
  };
};
