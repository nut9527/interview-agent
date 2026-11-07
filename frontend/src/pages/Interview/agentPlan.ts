import type { InterviewAgentState } from '@/types/api.d';

/** 保存的计划代表原始范围，展示顺序只由已提交的决策决定。 */
export function orderedAgentPlan(agent: InterviewAgentState) {
  if (agent.version !== 'interview-agent-v5') return agent.plan;
  const ids = [...new Set([...agent.evaluations.map(e => e.competencyId), agent.activeCompetencyId,
    ...agent.plan.map(c => c.id)])];
  const byId = new Map(agent.plan.map(c => [c.id, c]));
  return ids.flatMap(id => { const item = byId.get(id); return item ? [item] : []; });
}
