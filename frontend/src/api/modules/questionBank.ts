import { get } from '@/api/request';
import type { PageResult } from '@/types/api';
import type { Question, QuestionFilters, QuestionMetadata, QuestionSummary, QuestionProgress, QuestionPractice, QuestionDraw } from '@/types/questionBank';
import type { Schemas } from '@/types/generated/openapi';

export type ReadingReference = Required<Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$ReadingReference']>;

export const questionBankService = {
  progress: (signal?: AbortSignal) => get<QuestionProgress[]>('/api/questions/practice-status', { signal }),
  history: (id: string, page = 1, signal?: AbortSignal) =>
    get<PageResult<QuestionPractice>>('/api/questions/'+encodeURIComponent(id)+'/practices', { params: { page, size: 10 }, signal }),
  draw: (params: { topic?: string; difficulty?: string; count: number; roundsPerQuestion: number }, signal?: AbortSignal) =>
    get<QuestionDraw>('/api/questions/draw', { params, signal }),
  page: (params: QuestionFilters, signal?: AbortSignal) =>
    get<PageResult<QuestionSummary>>('/api/questions', { params, signal }),
  metadata: (signal?: AbortSignal) => get<QuestionMetadata>('/api/questions/metadata', { signal }),
  find: (id: string, signal?: AbortSignal) => get<Question>('/api/questions/' + encodeURIComponent(id), { signal }),
  references: (id: string, signal?: AbortSignal) =>
    get<ReadingReference[]>('/api/questions/' + encodeURIComponent(id) + '/references', { signal }),
};

