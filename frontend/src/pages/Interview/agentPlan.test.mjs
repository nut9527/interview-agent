import test from 'node:test';
import assert from 'node:assert/strict';
import { orderedAgentPlan } from './agentPlan.ts';

const plan = ['a', 'b', 'c', 'd'].map(id => ({ id }));
for (const version of ['interview-agent-v5', 'interview-agent-v6']) test(`${version} follows committed visits, preserves scope, and never mutates the original plan`, () => {
  const agent = { version, plan, activeCompetencyId: 'b',
    evaluations: [{ competencyId: 'a' }, { competencyId: 'a' }, { competencyId: 'd' }] };
  assert.deepEqual(orderedAgentPlan(agent).map(c => c.id), ['a', 'd', 'b', 'c']);
  assert.deepEqual(plan.map(c => c.id), ['a', 'b', 'c', 'd']);
  assert.deepEqual(orderedAgentPlan({ ...agent, evaluations: [agent.evaluations[0]], activeCompetencyId: 'd' })
    .map(c => c.id), ['a', 'd', 'b', 'c']);
  assert.deepEqual(orderedAgentPlan({ ...agent, evaluations: [], activeCompetencyId: 'a' }), plan);
});
test('legacy/formal plans retain their order; unknown persisted IDs cannot add a competency', () => {
  const agent = { version: 'interview-agent-v4', plan, activeCompetencyId: 'd', evaluations: [{ competencyId: 'a' }] };
  assert.equal(orderedAgentPlan(agent), plan);
  assert.deepEqual(orderedAgentPlan({ ...agent, version: 'interview-agent-v5', activeCompetencyId: 'unknown' })
    .map(c => c.id), ['a', 'b', 'c', 'd']);
});
