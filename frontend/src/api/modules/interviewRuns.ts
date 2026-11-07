import { get, post } from '../request';
import type { InterviewSessionRequest, SubmitAnswerRequest } from '@/types/api.d';
import { useAuthStore } from '@/stores/authStore';
import { createSseDecoder, type RunEvent } from '../../pages/Interview/runStream';
import { ApiError } from '../ApiError';

export type InterviewRun = Required<Omit<import('@/types/generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewRun'], 'sessionId' | 'errorCode'>> & {
  sessionId: string | null; errorCode: string | null;
};
export type InterviewRunTrace = import('@/types/generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewRunTrace'];
export const interviewRuns = {
  trace: (id: string, signal?: AbortSignal) => get<InterviewRunTrace>(`/api/interview-runs/${id}/steps`, { signal }),
  forSession: (id: string, signal?: AbortSignal) => get<InterviewRun[]>(`/api/interviews/sessions/${id}/runs`, { signal }),
  start: (configuration: InterviewSessionRequest, requestId: string, signal?: AbortSignal) =>
    post<InterviewRun>('/api/interviews/start-commands', { configuration, requestId }, { signal }),
  answer: (id: string, data: SubmitAnswerRequest, signal?: AbortSignal) =>
    post<InterviewRun>(`/api/interviews/sessions/${id}/answer-commands`, data, { signal }),
  get: (id: string, signal?: AbortSignal) => get<InterviewRun>(`/api/interview-runs/${id}`, { signal }),
  byCommand: (operation: string, requestId: string, signal?: AbortSignal) =>
    get<InterviewRun>('/api/interview-runs/by-command', { params: { operation, requestId }, signal }),
  active: (signal?: AbortSignal) => get<InterviewRun[]>('/api/interview-runs', { signal }),
  cancel: (id: string, signal?: AbortSignal) => post<InterviewRun>(`/api/interview-runs/${id}/cancel`, {}, { signal }),
  /** 读取 after 之后的事件；调用方须在账号或会话切换时取消 signal，并负责重连与查询终态。 */
  stream: async (id: string, after: number, signal: AbortSignal, onEvent: (event: RunEvent) => void) => {
    const token = useAuthStore.getState().token;
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/interview-runs/${id}/events?afterSeq=${after}`, {
      headers: { Authorization: `Bearer ${token}` }, signal,
    });
    if (!response.ok || !response.body) throw new ApiError('进度连接暂不可用', { status: response.status });
    const reader = response.body.getReader(), decoder = new TextDecoder(), consume = createSseDecoder(onEvent);
    try {
      for (;;) {
        const chunk = await reader.read();
        consume(decoder.decode(chunk.value, { stream: !chunk.done }), chunk.done);
        if (chunk.done) break;
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  },
};
