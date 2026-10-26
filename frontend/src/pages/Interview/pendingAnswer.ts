import { onAccountReset } from '../../api/accountScope.ts';
import type { InterviewSession } from '@/types/api.d';

export interface PendingAnswer {
  sessionId: string;
  expectedTurn: number;
  answer: string;
  requestId: string;
  expiresAt: number;
}

const TTL_MS = 10 * 60 * 1000;
const activeKey = (userId: number) => `interview:active:${userId}`;
const answerKey = (userId: number, sessionId: string, turn: number) =>
  `interview:pending:${userId}:${sessionId}:${turn}`;

export const pruneExpiredPendingAnswers = () => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index--) {
      const key = sessionStorage.key(index);
      if (!key?.startsWith('interview:pending:')) continue;
      try {
        const value = JSON.parse(sessionStorage.getItem(key) ?? '') as PendingAnswer;
        if (!Number.isFinite(value.expiresAt) || value.expiresAt <= Date.now()) sessionStorage.removeItem(key);
      } catch { sessionStorage.removeItem(key); }
    }
  } catch { /* Storage may be unavailable. */ }
};

export const rememberActiveSession = (userId: number, sessionId: string | null) => {
  try {
    if (sessionId) sessionStorage.setItem(activeKey(userId), sessionId);
    else sessionStorage.removeItem(activeKey(userId));
  } catch { /* Storage may be unavailable; the current page still works. */ }
};

export const getActiveSession = (userId: number) => {
  try { return sessionStorage.getItem(activeKey(userId)); } catch { return null; }
};

export const rememberPendingAnswer = (userId: number, pending: Omit<PendingAnswer, 'expiresAt'>) => {
  pruneExpiredPendingAnswers();
  const value: PendingAnswer = { ...pending, expiresAt: Date.now() + TTL_MS };
  const key = answerKey(userId, value.sessionId, value.expectedTurn);
  try { sessionStorage.setItem(key, JSON.stringify(value)); }
  catch { /* The in-memory request remains usable until navigation. */ }
  return value;
};

export const forgetPendingAnswer = (userId: number, pending: PendingAnswer) => {
  try { sessionStorage.removeItem(answerKey(userId, pending.sessionId, pending.expectedTurn)); } catch { /* no-op */ }
};

export const getPendingAnswer = (userId: number, sessionId: string): PendingAnswer | null => {
  for (let turn = 1; turn <= 12; turn++) {
    const key = answerKey(userId, sessionId, turn);
    try {
      const raw = sessionStorage.getItem(key);
      if (!raw) continue;
      const value = JSON.parse(raw) as PendingAnswer;
      if (value.sessionId === sessionId && value.expectedTurn === turn
          && typeof value.answer === 'string' && typeof value.requestId === 'string'
          && value.answer.length <= 4000 && /^[A-Za-z0-9_-]{8,80}$/.test(value.requestId)
          && value.expiresAt > Date.now()) return value;
      sessionStorage.removeItem(key);
    } catch { try { sessionStorage.removeItem(key); } catch { /* no-op */ } }
  }
  return null;
};

export const pendingResult = (session: InterviewSession, pending: PendingAnswer) => {
  if (session.turns.some((turn) => turn.speaker === 'user' && turn.requestId === pending.requestId)) return 'committed';
  if (session.state === 'FINISHED' || session.turnCount !== pending.expectedTurn) return 'stale';
  if (session.answerLeaseUntil && Date.parse(session.answerLeaseUntil) > Date.now()) return 'waiting';
  return 'retry';
};

// Logout/account changes remove the previous identity's short-lived recovery data.
onAccountReset(() => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index--) {
      const key = sessionStorage.key(index);
      if (key?.startsWith('interview:pending:') || key?.startsWith('interview:active:')) sessionStorage.removeItem(key);
    }
  } catch { /* Storage may be unavailable. */ }
});
