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
  } catch { /* 浏览器禁用存储时跳过清理，不阻断当前页面。 */ }
};

export const rememberActiveSession = (userId: number, sessionId: string | null) => {
  try {
    if (sessionId) sessionStorage.setItem(activeKey(userId), sessionId);
    else sessionStorage.removeItem(activeKey(userId));
  } catch { /* 存储不可用时仅失去刷新后的会话定位，当前页面仍可继续。 */ }
};

export const getActiveSession = (userId: number) => {
  try { return sessionStorage.getItem(activeKey(userId)); } catch { return null; }
};

/** 按账号、会话和轮次短期保存原回答及幂等键；写入失败仍返回可在当前页面核对的内存副本。 */
export const rememberPendingAnswer = (userId: number, pending: Omit<PendingAnswer, 'expiresAt'>) => {
  pruneExpiredPendingAnswers();
  const value: PendingAnswer = { ...pending, expiresAt: Date.now() + TTL_MS };
  const key = answerKey(userId, value.sessionId, value.expectedTurn);
  try { sessionStorage.setItem(key, JSON.stringify(value)); }
  catch { /* 写入失败时保留内存副本，离开页面后无法恢复。 */ }
  return value;
};

export const forgetPendingAnswer = (userId: number, pending: PendingAnswer) => {
  try { sessionStorage.removeItem(answerKey(userId, pending.sessionId, pending.expectedTurn)); } catch { /* 清理失败不改变已经核对的提交结果，恢复时仍须检查回执。 */ }
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
    } catch { try { sessionStorage.removeItem(key); } catch { /* 无法删除的无效记录也不参与本次恢复。 */ } }
  }
  return null;
};

/** 先用 requestId 核对持久回执；未提交时再检查轮次和租约，租约到期本身不代表提交成功。 */
export const pendingResult = (session: InterviewSession, pending: PendingAnswer) => {
  if (session.turns.some((turn) => turn.speaker === 'user' && turn.requestId === pending.requestId)) return 'committed';
  if (session.state === 'FINISHED' || session.turnCount !== pending.expectedTurn) return 'stale';
  if (session.answerLeaseUntil && Date.parse(session.answerLeaseUntil) > Date.now()) return 'waiting';
  return 'retry';
};

// 退出或切换账号时清理旧身份的短期恢复数据。
onAccountReset(() => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index--) {
      const key = sessionStorage.key(index);
      if (key?.startsWith('interview:pending:') || key?.startsWith('interview:active:')) sessionStorage.removeItem(key);
    }
  } catch { /* 存储不可用时跳过清理，后续读取仍按账号键隔离。 */ }
});
