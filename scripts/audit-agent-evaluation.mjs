// 只读历史证据，不访问数据库、.env 或供应商；另写 v2 审计，不覆盖历史结论/盲评。
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { summarizeAgentRows } from './agent-evaluation-metrics.mjs';
const directory = new URL('../docs/evidence/', import.meta.url);
const ledger = JSON.parse(await readFile(new URL('kernel-model-budget.json', directory), 'utf8'));
const rows = [];
for (const experiment of ledger.experiments) {
  if (experiment.state === 'PRE_MODEL_FAILURE') continue;
  const text = await readFile(new URL(experiment.report, directory), 'utf8');
  for (const line of text.trim().split('\n').filter(Boolean)) {
    rows.push({ ...JSON.parse(line), policy: experiment.policy, report: experiment.report });
  }
}
const failures = rows.filter(r => r.status !== 'SUCCEEDED').map(row => {
  const candidate = row.lastPublicCandidate || '';
  let parsed;
  try { parsed = JSON.parse(candidate); } catch { /* 无候选/非法 JSON 与缺少历史响应均单列。 */ }
  const feedback = (row.validationFailures || []).join('\n');
  const reasons = [];
  if (feedback.includes('观察与证据状态不一致')) reasons.push('OBSERVATION_CLAIM_CONFLICT');
  if (feedback.includes('引文偏移无效或拆开 emoji')) reasons.push('CITATION_OFFSET_REJECTED_CAUSE_UNRESOLVED');
  return { runId: row.runId, report: row.report, family: row.family, policy: row.policy,
    prompt: row.config.prompt, model: row.config.model, terminalError: row.error,
    inferredPhase: row.error === 'MODEL_OUTPUT_INVALID' ? 'VALIDATE' : row.error === 'MODEL_UNAVAILABLE' ? 'MODEL' : 'UNKNOWN',
    phaseBasis: 'terminal error mapping; not historical phase ledger or exact attempt association',
    attempts: (row.attemptLedger || []).map(a => ({ ordinal: a.ordinal, status: a.status, errorCode: a.error_code })),
    retainedFinalCandidate: candidate ? { sha256: createHash('sha256').update(candidate).digest('hex'),
      validJsonObject: !!parsed && typeof parsed === 'object' && !Array.isArray(parsed),
      scope: 'LAST_CANDIDATE_ONLY; not all attempt responses or first-output validation' } : null,
    historicalRejectionCategories: reasons,
    diagnosis: row.error === 'MODEL_UNAVAILABLE' ? 'No output replay; transport/availability failure must not be counted as output-schema failure.'
      : reasons.length ? 'Historical feedback identifies a validation category, not its precise attempt or implementation root cause.'
        : candidate ? 'Last candidate retained, but historical rejection detail unavailable; exact first failure cannot be recovered.'
          : 'No candidate or rejection detail retained; output failure cannot be replayed from this export.',
    fullAttemptReplay: 'UNAVAILABLE_IN_EXPORTED_EVIDENCE; evaluation account was cleaned after export' };
});
const groups = [...new Set(rows.map(r => `${r.policy}/${r.config.prompt}`))].map(group => ({ group,
  metrics: summarizeAgentRows(rows.filter(r => `${r.policy}/${r.config.prompt}` === group)) }));
const report = { version: 'agent-offline-audit-v1', source: 'retained synthetic development exports',
  acceptedRuns: rows.length, failures, groups,
  additionalProviderCalls: 0, qualityGate: 'NOT_PASSED', policyBenefit: 'INCONCLUSIVE', heldoutExecuted: false,
  nextExperiment: { precondition: 'Reconcile unknown usage or explicitly authorize a new budget before admission.',
    cases: '2 families (evidence missing / adequate evidence) x baseline/candidate = 4 runs',
    conservativeMaximumCnyAtHistoricalPrice: 4 * 4 * (480000 * 2 + 2000 * 8) / 1000000,
    note: 'Historical reservation formula only; recheck price and actual enforced limits before a new campaign. Not authorization.' },
  limitations: ['No live database or external provider was accessed.',
    'Historical elapsedMs measures worker execution, not admission-to-terminal wall time.',
    'Aggregate historical groups are not a frozen paired experiment.',
    'Targeted server regression fixtures do not reconstruct unavailable attempt responses.'] };
await writeFile(new URL('agent-offline-audit-09.json', directory), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ accepted: rows.length, failed: failures.length,
  retainedFailureCandidates: failures.filter(r => r.retainedFinalCandidate).length,
  report: 'docs/evidence/agent-offline-audit-09.json', providerCalls: 0 }));
