import type {
  PageResult, InterviewHistoryItem,
  InterviewSessionRequest,
  InterviewSession,
  SubmitAnswerRequest,
} from '@/types/api.d';
import { del, get, post } from '@/api/request';
import { CanceledError } from 'axios';
import { captureAccountScope, onAccountReset } from '../accountScope';
import { ApiError } from '../ApiError';
import { USE_MOCK } from '@/config/runtime';

const interviewQuestions: string[] = [
  '请介绍一段最能证明你胜任目标岗位的经历，并说明你的具体职责和结果。',
  '在刚才的经历中，你遇到的最大难点是什么？请说明你的判断和行动。',
  '如果重新完成这个任务，你会优先改进哪一点，为什么？',
];

const mockSessions: Record<string, InterviewSession> = {};
const mockSessionTimes: Record<string, { createdAt: string; updatedAt: string }> = {};

onAccountReset(() => {
  Object.keys(mockSessions).forEach((id) => { delete mockSessions[id]; });
  Object.keys(mockSessionTimes).forEach((id) => { delete mockSessionTimes[id]; });
});

const toMockHistoryItem = (session: InterviewSession): InterviewHistoryItem => {
  const timestamp = mockSessionTimes[session.sessionId];
  const lastInterviewerTurn = [...session.turns]
    .reverse()
    .find((turn) => turn.speaker === 'interviewer');
  return {
    sessionId: session.sessionId,
    targetRole: session.targetRole,
    resumeId: session.resumeId,
    mode: session.mode,
    totalRounds: session.totalRounds,
    state: session.state,
    turnCount: session.turnCount,
    answeredCount: session.turns.filter((turn) => turn.speaker === 'user').length,
    preview: lastInterviewerTurn?.content ?? '暂无面试内容',
    createdAt: timestamp?.createdAt ?? new Date().toISOString(),
    updatedAt: timestamp?.updatedAt ?? new Date().toISOString(),
  };
};

export const interviewService = {
  getRetries: (sessionId: string, page = 1, signal?: AbortSignal): Promise<PageResult<InterviewHistoryItem>> => {
    if (USE_MOCK) return Promise.resolve({ items: [], total: 0, page, size: 20 });
    return get(`/api/interviews/sessions/${encodeURIComponent(sessionId)}/retries`, { params: { page, size: 20 }, signal });
  },
  getSessions: (page = 1, signal?: AbortSignal): Promise<PageResult<InterviewHistoryItem>> => {
    if (USE_MOCK) {
      const rows = Object.values(mockSessions).map(toMockHistoryItem)
        .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt) || right.sessionId.localeCompare(left.sessionId));
      return Promise.resolve({ items: rows.slice((page-1)*20,page*20), total: rows.length, page, size: 20 });
    }
    return get<PageResult<InterviewHistoryItem>>('/api/interviews/sessions', { params: { page, size: 20 }, signal });
  },
  getSession: (sessionId: string, signal?: AbortSignal): Promise<InterviewSession> => {
    if (USE_MOCK) {
      const session = mockSessions[sessionId];
      return session ? Promise.resolve(session) : Promise.reject(new ApiError('历史面试记录不存在', { status: 404 }));
    }
    return get<InterviewSession>(`/api/interviews/sessions/${sessionId}`, { signal });
  },
  startSession: async (data: InterviewSessionRequest, signal?: AbortSignal): Promise<InterviewSession> => {
    if (USE_MOCK) {
      if (data.targetJobId || data.trainingTopic || data.retrySessionId) throw new Error('专项训练、薄弱点复练及完整材料流程需要连接后端，后端支持无密钥的 Mock AI。');
      const scope = captureAccountScope();
      if (!scope.isCurrent() || signal?.aborted) throw new CanceledError();
      const sessionId = `session-${Date.now()}`;
      const mockSession: InterviewSession = {
        sessionId,
        targetRole: data.targetRole,
        resumeId: data.resumeId ?? null,
        resumeSnapshot: data.resumeSummary ?? '',
        resumeRevision: null,
        jobId: null,
        jobSnapshot: `岗位：${data.targetRole}${data.jobDescription ? `\nJD：${data.jobDescription}` : ''}`,
        difficulty: data.difficulty ?? 'STANDARD',
        mode: data.mode,
        totalRounds: data.totalRounds,
        answerLeaseUntil: null,
        state: 'WAITING_ANSWER',
        turnCount: 1,
        turns: [
          {
            speaker: 'system' as const,
            content: data.resumeId
              ? `已载入所选简历。本轮将围绕简历经历和“${data.targetRole}”岗位进行 ${data.totalRounds} 轮提问。`
              : `欢迎来到模拟面试！目标岗位：${data.targetRole}。面试将开始，请准备回答问题。`,
          },
          {
            speaker: 'interviewer' as const,
            content: interviewQuestions[0],
          },
        ],
      };
      mockSessions[sessionId] = mockSession;
      const now = new Date().toISOString();
      mockSessionTimes[sessionId] = { createdAt: now, updatedAt: now };
      return new Promise((resolve) => {
        setTimeout(() => resolve(mockSession), 1500);
      });
    }
    return post<InterviewSession>('/api/interviews/sessions', data, { timeout: 60000, signal });
  },
  submitAnswer: (sessionId: string, data: SubmitAnswerRequest, signal?: AbortSignal): Promise<InterviewSession> => {
    if (USE_MOCK) {
      const existingSession = mockSessions[sessionId];
      if (!existingSession) {
        return Promise.reject(new ApiError('会话不存在', { status: 404 }));
      }

      const replayIndex = data.requestId
        ? existingSession.turns.findIndex((turn) => turn.speaker === 'user' && turn.requestId === data.requestId)
        : -1;
      if (replayIndex >= 0) {
        const answered = existingSession.turns.slice(0, replayIndex + 1).filter((turn) => turn.speaker === 'user').length;
        if (existingSession.turns[replayIndex].content !== data.answer.trim()
          || (data.expectedTurn !== undefined && data.expectedTurn !== answered)) {
          return Promise.reject(new ApiError('该请求编号已用于另一份回答', { status: 409 }));
        }
        return Promise.resolve({ ...existingSession,
          turns: existingSession.turns.slice(0, replayIndex + 3),
          state: answered >= existingSession.totalRounds ? 'FINISHED' : 'WAITING_ANSWER',
          turnCount: Math.min(answered + 1, existingSession.totalRounds),
        });
      }
      if (existingSession.state === 'FINISHED') return Promise.resolve(existingSession);
      if (data.expectedTurn !== undefined && data.expectedTurn !== existingSession.turnCount) {
        return Promise.reject(new ApiError('面试题目已更新，请重新打开当前会话后回答。', { status: 409 }));
      }

      const answeredCount = existingSession.turns.filter((turn) => turn.speaker === 'user').length + 1;
      const isLastQuestion = answeredCount >= existingSession.totalRounds;

      const newTurns: InterviewSession['turns'] = [
        {
          speaker: 'user' as const,
          content: data.answer.trim(),
          requestId: data.requestId,
        },
        {
          speaker: 'system' as const,
          content: data.answer.length < 40
            ? '即时反馈：回答较简短，建议补充背景、个人行动和结果。'
            : '即时反馈：回答信息量较完整，建议继续补充真实可核验的成果指标。',
        },
      ];

      if (isLastQuestion) {
        newTurns.push({
          speaker: 'interviewer',
          content: `面试总结：你完成了 ${answeredCount} 轮回答。建议继续使用 STAR 结构，并准备可深入追问的项目细节。`,
        });
      } else {
        newTurns.push({
          speaker: 'interviewer',
          content: interviewQuestions[answeredCount % interviewQuestions.length],
        });
      }

      const updatedSession: InterviewSession = {
        ...existingSession,
        state: isLastQuestion ? 'FINISHED' : 'WAITING_ANSWER',
        turnCount: isLastQuestion ? existingSession.totalRounds : answeredCount + 1,
        turns: [...existingSession.turns, ...newTurns],
      };

      mockSessions[sessionId] = updatedSession;
      const createdAt = mockSessionTimes[sessionId]?.createdAt ?? new Date().toISOString();
      mockSessionTimes[sessionId] = { createdAt, updatedAt: new Date().toISOString() };

      return new Promise((resolve) => {
        setTimeout(() => resolve(updatedSession), 2000);
      });
    }
    return post<InterviewSession>(`/api/interviews/sessions/${sessionId}/answers`, data, { timeout: 90000, signal });
  },
  deleteSession: (sessionId: string, signal?: AbortSignal): Promise<null> => {
    if (USE_MOCK) {
      if (!mockSessions[sessionId]) {
        return Promise.reject(new Error('历史面试记录不存在'));
      }
      delete mockSessions[sessionId];
      delete mockSessionTimes[sessionId];
      return Promise.resolve(null);
    }
    return del<null>(`/api/interviews/sessions/${sessionId}`, { signal });
  },
};
