import { get, post } from '@/api/request';
import type { Schemas } from '@/types/generated/openapi';

export type Goal = Required<Schemas['cn.edu.whu.zhizhida.learning.api.LearningGoal']>;
export type GoalPlan = Required<Schemas['cn.edu.whu.zhizhida.learning.api.LearningGoal$Plan']>;
export type PlanItem = Required<Schemas['cn.edu.whu.zhizhida.learning.api.GoalTraining$Item']>;
export type GoalReview = Required<Schemas['cn.edu.whu.zhizhida.learning.api.LearningGoal$Review']>;
const base = '/api/learning';
export const learningGoals = {
  list: (signal?: AbortSignal) => get<Goal[]>(`${base}/goals`, { signal }),
  create: (body: Schemas['cn.edu.whu.zhizhida.learning.api.LearningGoal$Create'], signal?: AbortSignal) => post<Goal>(`${base}/goals`, body, { signal }),
  status: (id: string, expectedRevision: number, action: 'PAUSE' | 'RESUME' | 'CANCEL', signal?: AbortSignal) => post<Goal>(`${base}/goals/${id}/status`, { expectedRevision, action }, { signal }),
  propose: (id: string, expectedRevision: number, requestId: string, signal?: AbortSignal, observationIds: string[] = []) => post<Required<Schemas['cn.edu.whu.zhizhida.common.execution.ExecutionPort$Run']>>(`${base}/goals/${id}/plan-commands`, { expectedRevision, requestId, observationIds }, { signal }),
  edit: (id: string, expectedRevision: number, items: PlanItem[], signal?: AbortSignal) => post<GoalPlan>(`${base}/plans/${id}/revisions`, { expectedRevision, items }, { signal }),
  reject: (id: string, expectedRevision: number, signal?: AbortSignal) => post<GoalPlan>(`${base}/plans/${id}/reject`, { expectedRevision }, { signal }),
  confirm: (id: string, expectedRevision: number, requestId: string, signal?: AbortSignal) => post<GoalPlan>(`${base}/plans/${id}/confirm`, { expectedRevision, requestId }, { signal }),
  review: (id: string, signal?: AbortSignal) => post<GoalReview>(`${base}/goals/${id}/review`, {}, { signal }),
  criteria: (id: string, expectedRevision: number, requiredScenarios: number, dimensions: string[], signal?: AbortSignal) => post<Goal>(`${base}/goals/${id}/criteria`, { expectedRevision, requiredScenarios, dimensions, confirmed: true }, { signal }),
  complete: (id: string, review: GoalReview, requestId: string, reviewNote: string, signal?: AbortSignal) => post<Goal>(`${base}/goals/${id}/complete`, { requestId, expectedRevision: review.goalRevision, evidenceHash: review.evidenceHash, reviewNote, confirmed: true }, { signal }),
};
