// 汇总各执行路线的运行证据，并生成独立的盲评材料与身份对照表。
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const base = new URL('../docs/evidence/', import.meta.url);
const rows = [];
for (const route of ['legacy', 'fixed', 'tool']) {
  try {
    for (const line of (await readFile(new URL(`agent-evaluation-${route}.jsonl`, base), 'utf8')).trim().split('\n')) rows.push(JSON.parse(line));
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
if (!rows.length) throw new Error('Run at least one evaluation route first');
const quantile = (values, q) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(values.length * q) - 1] : null;
const groups = {};
for (const route of ['LEGACY', 'FIXED', 'TOOL']) {
  const subset = rows.filter(r => r.route === route), elapsed = subset.map(r => r.elapsedMs);
  // 未报告用量的调用单独计数，不能当作零消耗并入已知令牌数。
  let knownTokens = 0, unknownUsageAttempts = 0;
  for (const row of subset) for (const attempt of row.attemptLedger) {
    const usage = typeof attempt.usage_json === 'string' ? JSON.parse(attempt.usage_json) : attempt.usage_json;
    if (usage?.status === 'REPORTED') knownTokens += usage.totalTokens;
    else unknownUsageAttempts++;
  }
  groups[route] = { samples: subset.length, succeeded: subset.filter(r => r.status === 'SUCCEEDED').length,
    failed: subset.filter(r => r.status !== 'SUCCEEDED').length, p50Ms: quantile(elapsed, .5), p95Ms: quantile(elapsed, .95),
    modelAttempts: subset.reduce((s, r) => s + r.attempts, 0), toolExecutions: subset.reduce((s, r) => s + r.toolExecutions, 0),
    repairs: subset.reduce((s, r) => s + r.repairs, 0), knownTokens, unknownUsageAttempts };
}
const sameInputs = rows.every(a => rows.filter(b => b.caseId === a.caseId).every(b => b.sourceHash === a.sourceHash && JSON.stringify(a.config) === JSON.stringify(b.config)));
// 人工评分字段留空；成功率与协议有效性本身不能代表回答的语义质量。
const blinded = rows.filter(r => r.status === 'SUCCEEDED').map(row => ({
  blindId: createHash('sha256').update(row.runId).digest('hex').slice(0, 16),
  question: row.fixture.question, answer: row.fixture.answer, source: row.fixture.material,
  nextQuestion: row.nextQuestion, evaluation: row.evaluation,
  review: { questionRelevantToGap: null, evidenceSupportsConclusion: null, unnecessaryToolUse: null, unsupportedCorrection: null, notes: '' },
})).sort((a, b) => a.blindId.localeCompare(b.blindId));
await writeFile(new URL('agent-blind-review.jsonl', base), blinded.map(r => JSON.stringify(r)).join('\n') + '\n');
await writeFile(new URL('agent-blind-key.json', base), JSON.stringify(rows.map(r => ({ blindId: createHash('sha256').update(r.runId).digest('hex').slice(0, 16), route: r.route, caseId: r.caseId })), null, 2) + '\n');
const report = { groups, sameSourcesAndConfiguration: sameInputs,
  quality: 'Unscored: blinded human review required. JSON validity and HTTP success are not semantic quality scores.',
  interpretation: 'Protocol smoke samples only; p50/p95 show observed samples, not production performance or pricing.' };
await writeFile(new URL('agent-evaluation-summary.json', base), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
