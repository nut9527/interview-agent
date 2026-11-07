// 汇总时将失败样本计入分母，并生成待人工评分的评审材料，不代填人工判断。
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { summarizeAgentRows } from './agent-evaluation-metrics.mjs';
const directory = new URL('../docs/evidence/', import.meta.url);
const ledger = JSON.parse(await readFile(new URL('kernel-model-budget.json', directory), 'utf8'));
const rows = [];
for (const experiment of ledger.experiments) {
  if (experiment.state === 'PRE_MODEL_FAILURE') continue;
  const report = (await readFile(new URL(experiment.report, directory), 'utf8')).trim();
  for (const line of report.split('\n').filter(Boolean)) rows.push({ ...JSON.parse(line), policy: experiment.policy, report: experiment.report });
}
const ids = rows.map(r => createHash('sha256').update(r.runId).digest('hex').slice(0, 16));
// 策略与提示词版本共同分组，避免把不同版本的开发结果混作同一组实验。
const groups = [...new Set(rows.map(r => r.policy + '/' + r.config.prompt))].map(group => {
  const selected = rows.filter(r => r.policy + '/' + r.config.prompt === group);
  return { group, accepted: selected.length, succeeded: selected.filter(r => r.status === 'SUCCEEDED').length,
    failed: selected.filter(r => r.status !== 'SUCCEEDED').length, families: [...new Set(selected.map(r => r.family))],
    observedElapsedMs: selected.map(r => r.elapsedMs), attempts: selected.reduce((n, r) => n + r.attempts, 0),
    metrics: summarizeAgentRows(selected),
    toolRequests: selected.every(r => Array.isArray(r.modelToolRequests))
      ? selected.reduce((n, r) => n + r.modelToolRequests.length, 0) : null,
    failures: selected.filter(r => r.status !== 'SUCCEEDED').map(r => ({ caseId: r.caseId, error: r.error, validationFailures: r.validationFailures || [] })) };
});
const blind = rows.map((r, index) => ({ blindId: ids[index], family: r.family, question: r.fixture.question, answer: r.fixture.answer, source: r.fixture.material,
  outcome: r.status, validatedEvaluation: r.status === 'SUCCEEDED' ? r.evaluation : null,
  validatedNextQuestion: r.status === 'SUCCEEDED' ? r.nextQuestion : null,
  review: { evidenceSupportsConclusion: null, questionRelevantToGap: null, unsupportedCorrection: null, independentReviewer: null, notes: '' } })).sort((a, b) => a.blindId.localeCompare(b.blindId));
await writeFile(new URL('kernel-blind-review.jsonl', directory), blind.map(r => JSON.stringify(r)).join('\n') + '\n');
await writeFile(new URL('kernel-blind-key.json', directory), JSON.stringify(rows.map((r, i) => ({ blindId: ids[i], policy: r.policy, prompt: r.config.prompt, report: r.report, runId: r.runId })), null, 2) + '\n');
// 分别展示已报告用量估算和含未知消耗的预占总额，二者都不视为供应商账单。
const knownMicro = ledger.experiments.reduce((sum, e) => sum + (e.reportedInputTokens || 0) * ledger.price.inputMicroCnyPerToken + (e.reportedOutputTokens || 0) * ledger.price.outputMicroCnyPerToken, 0);
const retainedMicro = ledger.experiments.reduce((sum, e) => sum + e.retainedMicroCny, 0);
const report = { authorizedCapCny: ledger.authorizedCapMicroCny / 1000000,
  pricing: ledger.price, reportedUsageAtConservativePriceCny: knownMicro / 1000000,
  retainedIncludingUnknownCny: retainedMicro / 1000000, unknownAttempts: ledger.experiments.reduce((n, e) => n + (e.unknownAttempts || 0), 0),
  remainingConservativeCny: (ledger.authorizedCapMicroCny - retainedMicro) / 1000000,
  actualInvoice: 'UNKNOWN: no provider invoice or account balance reconciliation',
  acceptedRuns: rows.length, groups, heldoutExecuted: false, independentHumanReview: 'PENDING',
  qualityGate: 'NOT_PASSED', policyBenefit: 'INCONCLUSIVE',
  limitations: ['Only two development families; repeated variants are not independent samples.', 'Prompt versions differ after development repair; groups are separate, not a frozen paired release experiment.', 'Observed latency includes TLS errors; not a production p50/p95 or load result.', 'No tool execution occurred in these samples, so they do not demonstrate observation-driven adaptation.', 'Rejected outputs remain failures and are not presented as validated assessments.', 'Unknown usage retains the maximum admitted per-attempt reservation. No further run fits the remaining conservative budget.'] };
await writeFile(new URL('kernel-real-summary.json', directory), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ accepted: report.acceptedRuns, retainedCny: report.retainedIncludingUnknownCny, reportedUsagePriceCny: report.reportedUsageAtConservativePriceCny, quality: report.qualityGate }));
