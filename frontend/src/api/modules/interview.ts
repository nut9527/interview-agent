import { interviewRuns } from './interviewRuns.ts';
import { acceptRunCommand } from './runCommand.ts';
import type { PageResult, InterviewHistoryItem, InterviewSessionRequest, InterviewSession } from '@/types/api.d';
import { del, get } from '../request.ts';
import { CanceledError } from 'axios';
import { captureAccountScope } from '../accountScope.ts';

export const interviewService = {
  runs: interviewRuns,
  getRetries: (sessionId: string, page = 1, signal?: AbortSignal): Promise<PageResult<InterviewHistoryItem>> => {
    return get(`/api/interviews/sessions/${encodeURIComponent(sessionId)}/retries`, {
      params: { page, size: 20 },
      signal,
    });
  },
  getSessions: (page = 1, signal?: AbortSignal): Promise<PageResult<InterviewHistoryItem>> => {
    return get<PageResult<InterviewHistoryItem>>('/api/interviews/sessions', { params: { page, size: 20 }, signal });
  },
  getSession: (sessionId: string, signal?: AbortSignal): Promise<InterviewSession> => {
    return get<InterviewSession>(`/api/interviews/sessions/${sessionId}`, { signal });
  },
  startSession: async (
    data: InterviewSessionRequest,
    signal?: AbortSignal,
    practiceKey?: string,
  ): Promise<InterviewSession> => {
    const scope = captureAccountScope();
    const requestId = (practiceKey && sessionStorage.getItem(practiceKey)) || crypto.randomUUID();
    if (practiceKey) sessionStorage.setItem(practiceKey, requestId);
    const forget = () => {
      if (practiceKey && scope.isCurrent() && sessionStorage.getItem(practiceKey) === requestId) {
        sessionStorage.removeItem(practiceKey);
      }
    };
    let run = await acceptRunCommand(
      () => interviewService.runs.start(data, requestId, signal),
      () => {
        if (!scope.isCurrent() || signal?.aborted) throw new CanceledError();
        return interviewService.runs.byCommand('START', requestId, signal);
      },
      forget,
    );
    const deadline = Date.now() + 120000;
    while (!['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(run.status) && Date.now() < deadline) {
      if (!scope.isCurrent() || signal?.aborted) throw new CanceledError();
      await new Promise((resolve) => setTimeout(resolve, 750));
      run = await interviewService.runs.get(run.runId, signal);
    }
    if (run.status !== 'SUCCEEDED' || !run.sessionId) {
      if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(run.status)) forget();
      throw new Error(`面试启动未完成（${run.errorCode ?? run.status}），请到面试页面恢复。`);
    }
    const session = await interviewService.getSession(run.sessionId, signal);
    forget();
    return session;
  },
  deleteSession: (sessionId: string, signal?: AbortSignal): Promise<null> => {
    return del<null>(`/api/interviews/sessions/${sessionId}`, { signal });
  },
};
