import { del, get, post, put } from '@/api/request';
import type { PageResult } from '@/types/api.d';
import type { TargetJob, TargetJobInput, TargetJobSummary, TargetExtraction, TargetAnalysis } from '@/types/targetJob';

// 私有岗位始终由后端保存；不以浏览器内存伪装成持久化。
export const targetJobService = {
  list: (page: number, signal?: AbortSignal) => get<PageResult<TargetJobSummary>>('/api/target-jobs', { params: { page, size: 12 }, signal }),
  get: (id: string, signal?: AbortSignal) => get<TargetJob>(`/api/target-jobs/${encodeURIComponent(id)}`, { signal }),
  extract: (jdText: string, signal?: AbortSignal) => post<TargetExtraction>('/api/target-jobs/extract', { jdText }, { signal }),
  save: (id: string | undefined, input: TargetJobInput, signal?: AbortSignal) => id
    ? put<TargetJob>(`/api/target-jobs/${encodeURIComponent(id)}`, input, { signal })
    : post<TargetJob>('/api/target-jobs', input, { signal }),
  delete: (job: TargetJobSummary) => del<null>(`/api/target-jobs/${encodeURIComponent(job.id)}`, { params: { revision: job.revision } }),
  analyze: (job: TargetJob, resumeId: number, signal?: AbortSignal) => post<TargetAnalysis>(`/api/target-jobs/${encodeURIComponent(job.id)}/analysis`,
    { resumeId, revision: job.revision }, { signal }),
};
