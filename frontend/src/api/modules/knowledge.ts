import { get, post, put, del } from '../request.ts';
import { knowledgeUploadData } from './knowledgeUpload.ts';
import type { PageResult } from '@/types/api';
import type {
  KnowledgeDocument,
  KnowledgeImport,
  KnowledgeImportJob,
  KnowledgeSummary,
  KnowledgeHit,
  KnowledgeAnswer,
  KnowledgeAnswerSummary,
  KnowledgeAnswerRecord,
  ReferenceSummary,
} from '@/types/knowledge';
import type {
  KnowledgeOrganization,
  SelfTestSelection,
  KnowledgeSelfTest,
  SelfTestQuestion,
  KnowledgeFeedback,
  FeedbackReason,
  GenerationOptions,
} from '@/types/knowledge';
export const knowledgeService = {
  references: (signal?: AbortSignal) => get<ReferenceSummary[]>('/api/knowledge/library', { signal }),
  reference: (id: string, signal?: AbortSignal) =>
    get<KnowledgeImport>('/api/knowledge/library/' + encodeURIComponent(id), { signal }),
  importReference: (id: string, draft: KnowledgeImport) =>
    post<KnowledgeDocument>('/api/knowledge/library/' + encodeURIComponent(id) + '/import', {
      title: draft.title,
      sourceName: draft.sourceName,
      content: draft.content,
      confirmed: true,
    }),
  ask: (question: string, documentIds: string[], signal?: AbortSignal, parentAnswerId?: string) =>
    post<KnowledgeAnswer>(
      '/api/knowledge/answers',
      { question, documentIds, parentAnswerId },
      { signal, timeout: 120000 },
    ),
  organization: (signal?: AbortSignal) => get<KnowledgeOrganization[]>('/api/knowledge/organization', { signal }),
  organize: (documentIds: string[], category: string, tags: string[]) =>
    put('/api/knowledge/organization', { documentIds, category, tags }),
  generate: (sources: SelfTestSelection[], count: number, signal?: AbortSignal, options?: GenerationOptions) =>
    post<KnowledgeSelfTest[]>(
      '/api/knowledge/self-tests/generate',
      { sources, count, ...options },
      { signal, timeout: 120000 },
    ),
  selfTests: (confirmed: boolean, page = 1, signal?: AbortSignal) =>
    get<PageResult<KnowledgeSelfTest>>('/api/knowledge/self-tests', { params: { confirmed, page, size: 20 }, signal }),
  confirmQuestion: (id: string, question: SelfTestQuestion) =>
    post<KnowledgeSelfTest>('/api/knowledge/self-tests/' + encodeURIComponent(id) + '/confirm', {
      stem: question.stem,
      point: question.point,
      referencePoints: question.referencePoints,
      confirmed: true,
    }),
  deleteQuestion: (id: string) => del('/api/knowledge/self-tests/' + encodeURIComponent(id)),
  feedback: (id: string, signal?: AbortSignal) =>
    get<KnowledgeFeedback>('/api/knowledge/answers/' + encodeURIComponent(id) + '/feedback', { signal }),
  saveFeedback: (id: string, reasons: FeedbackReason[], comment: string, missingDocumentIds: string[]) =>
    put<KnowledgeFeedback>('/api/knowledge/answers/' + encodeURIComponent(id) + '/feedback', {
      reasons,
      comment,
      missingDocumentIds,
    }),
  feedbacks: (page = 1, signal?: AbortSignal) =>
    get<PageResult<KnowledgeFeedback>>('/api/knowledge/feedback', { params: { page, size: 20 }, signal }),
  answers: (page = 1, signal?: AbortSignal) =>
    get<PageResult<KnowledgeAnswerSummary>>('/api/knowledge/answers', { params: { page, size: 20 }, signal }),
  answer: (id: string, signal?: AbortSignal) =>
    get<KnowledgeAnswerRecord>('/api/knowledge/answers/' + encodeURIComponent(id), { signal }),
  deleteAnswer: (id: string) => del('/api/knowledge/answers/' + encodeURIComponent(id)),
  choices: (signal?: AbortSignal) =>
    get<PageResult<KnowledgeSummary>>('/api/knowledge/documents', { params: { page: 1, size: 100 }, signal }),
  find: (id: string, signal?: AbortSignal) =>
    get<KnowledgeDocument>('/api/knowledge/documents/' + encodeURIComponent(id), { signal }),
  version: (id: string, version: number, signal?: AbortSignal) =>
    get<KnowledgeDocument>(`/api/knowledge/documents/${encodeURIComponent(id)}/versions/${version}`, { signal }),
  versions: (id: string, page = 1, signal?: AbortSignal) =>
    get<PageResult<KnowledgeSummary>>(`/api/knowledge/documents/${encodeURIComponent(id)}/versions`, {
      params: { page, size: 20 },
      signal,
    }),
  edit: (id: string, content: string, expectedVersion: number) =>
    put<KnowledgeDocument>(`/api/knowledge/documents/${encodeURIComponent(id)}/content`, { content, expectedVersion }),
  restore: (id: string, version: number, expectedVersion: number) =>
    post<KnowledgeDocument>(`/api/knowledge/documents/${encodeURIComponent(id)}/versions/${version}/restore`, {
      expectedVersion,
    }),
  create: (title: string, content: string) => post<KnowledgeDocument>('/api/knowledge/documents', { title, content }),
  startImport: async (file: File, signal?: AbortSignal) =>
    post<KnowledgeImportJob>('/api/knowledge/imports/jobs', knowledgeUploadData(file), { signal, timeout: 120000 }),
  importJobs: (signal?: AbortSignal) => get<KnowledgeImportJob[]>('/api/knowledge/imports/jobs', { signal }),
  importResult: (id: string, signal?: AbortSignal) =>
    get<KnowledgeImport>('/api/knowledge/imports/jobs/' + encodeURIComponent(id) + '/result', { signal }),
  cancelImport: (id: string) => del('/api/knowledge/imports/jobs/' + encodeURIComponent(id)),
  confirmJob: (id: string, draft: KnowledgeImport) =>
    post<KnowledgeDocument>(
      '/api/knowledge/imports/jobs/' + encodeURIComponent(id) + '/confirm',
      { title: draft.title, sourceName: draft.sourceName, content: draft.content, confirmed: true },
      { timeout: 120000 },
    ),
  rename: (id: string, title: string) =>
    put<KnowledgeDocument>('/api/knowledge/documents/' + encodeURIComponent(id), { title }),
  delete: (id: string) => del('/api/knowledge/documents/' + encodeURIComponent(id)),
  search: (query: string, signal?: AbortSignal) =>
    get<KnowledgeHit[]>('/api/knowledge/search', { params: { query }, signal }),
};
