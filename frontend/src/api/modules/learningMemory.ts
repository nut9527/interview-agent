import { get, post, put } from '@/api/request';
import type { Schemas } from '@/types/generated/openapi';

export type LearningMemory = Schemas['cn.edu.whu.zhizhida.learning.api.LearningMemory'];
export type LearningObservation = Schemas['cn.edu.whu.zhizhida.learning.api.LearningMemory$Observation'];
const base = '/api/learning';
export const learningMemory = {
  list: (page: number, signal?: AbortSignal) => get<LearningMemory>(`${base}/observations`, { params: { page, size: 20 }, signal }),
  preferences: (personalizationEnabled: boolean, expectedRevision: number) => put<Schemas['cn.edu.whu.zhizhida.learning.api.LearningMemory$Preferences']>(`${base}/preferences`, { personalizationEnabled, expectedRevision }),
  correct: (id: string, expectedRevision: number, text: string) => post<LearningObservation>(`${base}/observations/${id}/corrections`, { expectedRevision, text }),
  remove: (id: string, expectedRevision: number) => post<void>(`${base}/observations/${id}/delete`, { expectedRevision }),
  export: (cursor: string, signal?: AbortSignal) => get<Schemas['cn.edu.whu.zhizhida.learning.api.LearningMemory$Export']>(`${base}/observations/export`, { params: { cursor }, signal }),
};
