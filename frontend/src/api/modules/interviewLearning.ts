import type { InterviewRun } from './interviewRuns';
import { del, get, post } from '@/api/request';
import type { InterviewDifficulty, PageResult } from '@/types/api.d';

export interface InterviewResources {
  round: number; competency: string; gap: string;
  passages: Array<{ documentId: string; title: string; version: number; chunkId: string; heading: string; startLine: number; endLine: number; content: string }>;
  exercises: Array<{ id: string; version: number; title: string; difficulty: InterviewDifficulty; reason: string }>;
}
export interface TrainingTask {
  id: string; sourceSessionId: string; sourceRound: number; questionId: string; title: string; gap: string;
  status: 'TODO' | 'STARTING' | 'ACTIVE' | 'PAUSED' | 'COMPLETED'; activeSessionId: string | null;
  answeredCount: number; totalRounds: number; createdAt: string;
  goalId?: string; questionVersion?: number;
  sourceType?: 'REVIEW' | 'GOAL' | 'QUESTION' | 'KNOWLEDGE'; sourceDocumentId?: string; sourceDocumentVersion?: number;
  trainingTopic?: 'DATABASE' | 'CONCURRENCY' | 'PERFORMANCE' | 'PROJECT';
}
export type DirectTrainingSource = { sourceType: 'QUESTION'; questionId: string; questionVersion: number }
  | { sourceType: 'KNOWLEDGE'; documentId: string; documentVersion: number; trainingTopic: NonNullable<TrainingTask['trainingTopic']> };
const base = '/api/interviews';
export const interviewLearning = {
  resources: (id: string, signal?: AbortSignal) => get<InterviewResources[]>(`${base}/sessions/${encodeURIComponent(id)}/resources`, { signal }),
  tasks: (page: number, signal?: AbortSignal) => get<PageResult<TrainingTask>>(`${base}/training-tasks`, { params: { page, size: 20 }, signal }),
  add: (sourceSessionId: string, sourceRound: number, questionId?: string, signal?: AbortSignal) => post<TrainingTask>(`${base}/training-tasks`, { sourceSessionId, sourceRound, questionId }, { signal }),
  addSource: (source: DirectTrainingSource, requestId: string, signal?: AbortSignal) => post<TrainingTask>(`${base}/training-tasks/from-source`, { ...source, requestId }, { signal }),
  update: (id: string, action: 'PAUSE' | 'RESUME', signal?: AbortSignal) => post<TrainingTask>(`${base}/training-tasks/${id}/status`, { action }, { signal }),
  startCommand: (id: string, requestId: string, signal?: AbortSignal) => post<InterviewRun>(`${base}/training-tasks/${id}/start-commands`, { requestId }, { signal }),
  remove: (id: string, signal?: AbortSignal) => del(`${base}/training-tasks/${id}`, { signal }),
};
