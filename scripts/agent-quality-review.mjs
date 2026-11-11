// Offline only: freeze non-heldout inputs, blind retained outputs, and summarize human scores.
import { readFile, writeFile, mkdir, lstat } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const routes = ['LEGACY', 'FIXED', 'TOOL'];
const dimensions = ['followUpRelevance', 'evidenceSupport', 'specificity'];
const canonical = value => JSON.stringify(value, (_, v) => v && typeof v === 'object' && !Array.isArray(v)
  ? Object.fromEntries(Object.keys(v).sort().map(key => [key, v[key]])) : v);
const hash = value => createHash('sha256').update(typeof value === 'string' ? value : canonical(value)).digest('hex');
const fail = message => { throw new Error(message); };
const nonempty = value => typeof value === 'string' && value.trim().length > 0;

export function makePlan(fixtures) {
  const partitions = new Map(), ids = new Set();
  for (const fixture of fixtures) {
    if (ids.has(fixture.id)) fail('Duplicate fixture ID');
    ids.add(fixture.id);
    if (partitions.has(fixture.family) && partitions.get(fixture.family) !== fixture.partition)
      fail('Family leakage across partitions');
    partitions.set(fixture.family, fixture.partition);
  }
  const selected = fixtures.filter(f => ['development', 'validation'].includes(f.partition));
  if (selected.length !== 30 || selected.filter(f => f.partition === 'development').length !== 20
      || selected.filter(f => f.partition === 'validation').length !== 10
      || new Set(selected.map(f => f.family)).size !== 30) fail('Expected 30 distinct non-heldout families (20 development + 10 validation)');
  return { version: 'agent-quality-plan-v1', routes, expectedRuns: selected.length * routes.length,
    cases: selected.map(f => ({ id: f.id, family: f.family, partition: f.partition,
      fixtureSha256: hash(f), sourceSha256: hash(f.material) })),
    rubric: { followUpRelevance: '1 偏题；3 相关但未触及关键缺口；5 准确追问缺口，证据充足时合理推进',
      evidenceSupport: '1 无依据或曲解原文；3 部分支持；5 结论与原文和回答一致',
      specificity: '1 空泛或不可回答；3 有方向但宽泛；5 清晰具体且可由候选人回答',
      unsupportedCorrection: '是否无依据纠错，true/false', repeatedQuestion: '是否重复原问题而无推进，true/false',
      notes: '人工给出评分依据；不得用模型评分或运行成功率代替' },
    scope: '30 synthetic single-turn families; report development and validation separately; heldout excluded',
    execution: 'NOT_EXECUTED_BY_THIS_TOOL; model execution requires separately frozen configuration and budget',
    conclusionRule: 'Descriptive paired score deltas only; no general quality-improvement claim from this tool' };
}

export function validateRows(plan, rows) {
  const seen = new Set();
  for (const row of rows) {
    const sample = plan.cases.find(c => c.id === row.caseId);
    if (!sample || !routes.includes(row.route)) fail('Unregistered case/route (heldout is excluded)');
    const key = `${row.caseId}/${row.route}`;
    if (seen.has(key)) fail('Duplicate case/route; do not select a favorable rerun');
    seen.add(key);
    if (hash(row.fixture) !== sample.fixtureSha256 || row.sourceHash !== sample.sourceSha256
        || row.partition !== sample.partition || row.family !== sample.family) fail('Frozen fixture or source differs');
    if (!nonempty(row.runId) || !row.config || typeof row.config !== 'object') fail('Missing run identity/configuration');
    if (!['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'SUPERSEDED'].includes(row.status)) fail('Non-terminal run');
    if (row.status === 'SUCCEEDED' && (!nonempty(row.nextQuestion) || !row.evaluation?.decision)) fail('Missing committed output');
  }
  if (new Set(rows.map(r => r.runId)).size !== rows.length) fail('Duplicate run ID');
}

// Whitelist only human-visible content. Tool receipts, routing/config metadata and timings are withheld.
const content = row => ({ question: row.fixture.question, answer: row.fixture.answer,
  source: row.fixture.material, nextQuestion: row.nextQuestion,
  assessment: Object.fromEntries(['level', 'evidence', 'gap', 'reason', 'practice', 'intent']
    .filter(k => row.evaluation.decision[k] !== undefined).map(k => [k, row.evaluation.decision[k]])) });

export function prepareReview(plan, rows) {
  validateRows(plan, rows);
  const key = [], reviews = [];
  for (const row of rows.filter(r => r.status === 'SUCCEEDED')) {
    const blindId = randomUUID(), visible = content(row);
    key.push({ blindId, runId: row.runId, caseId: row.caseId, route: row.route, contentSha256: hash(visible) });
    reviews.push({ blindId, ...visible, review: { reviewer: null, method: null, reviewedAt: null,
      followUpRelevance: null, evidenceSupport: null, specificity: null,
      unsupportedCorrection: null, repeatedQuestion: null, notes: '' } });
  }
  return { key, review: { version: 'agent-quality-review-v1', rubric: plan.rubric,
    instruction: 'Only edit review fields. Review independently without opening key.json, evidence.json or summary.json. method must be HUMAN_BLIND.',
    rows: reviews.sort((a, b) => a.blindId.localeCompare(b.blindId)) } };
}

export function summarizeReview(plan, rows, key, review) {
  validateRows(plan, rows);
  const successful = rows.filter(r => r.status === 'SUCCEEDED');
  if (review.version !== 'agent-quality-review-v1' || key.length !== successful.length || review.rows.length !== key.length
      || new Set(key.map(k => k.blindId)).size !== key.length || new Set(key.map(k => k.runId)).size !== key.length
      || new Set(review.rows.map(r => r.blindId)).size !== key.length) fail('Missing/duplicate review identity');
  const scored = new Map();
  for (const item of review.rows) {
    const identity = key.find(k => k.blindId === item.blindId);
    const row = successful.find(r => r.runId === identity?.runId);
    if (!row || identity.caseId !== row.caseId || identity.route !== row.route) fail('Review key differs from evidence');
    const { blindId, review: score, ...visible } = item;
    if (hash(visible) !== identity.contentSha256 || hash(content(row)) !== identity.contentSha256)
      fail('Blinded content changed after preparation');
    const untouched = dimensions.every(d => score[d] === null) && score.unsupportedCorrection === null
      && score.repeatedQuestion === null && score.reviewer === null && score.method === null
      && score.reviewedAt === null && score.notes === '';
    if (untouched) continue;
    if (!dimensions.every(d => Number.isInteger(score[d]) && score[d] >= 1 && score[d] <= 5)
        || typeof score.unsupportedCorrection !== 'boolean' || typeof score.repeatedQuestion !== 'boolean'
        || !nonempty(score.reviewer) || score.method !== 'HUMAN_BLIND' || !nonempty(score.notes)
        || !Number.isFinite(Date.parse(score.reviewedAt)) || Date.parse(score.reviewedAt) > Date.now())
      fail('Partial/invalid human review: complete every field or leave the row untouched');
    scored.set(row.runId, score);
  }
  const groups = routes.map(route => {
    const subset = rows.filter(r => r.route === route);
    return { route, expected: plan.cases.length, observed: subset.length,
      succeeded: subset.filter(r => r.status === 'SUCCEEDED').length,
      failed: subset.filter(r => r.status !== 'SUCCEEDED').length,
      missing: plan.cases.length - subset.length, reviewed: subset.filter(r => scored.has(r.runId)).length };
  });
  const comparisons = [];
  for (const partition of ['development', 'validation']) for (const [baseline, candidate] of [['LEGACY', 'FIXED'], ['FIXED', 'TOOL']]) {
    const pairs = [], excluded = [];
    for (const sample of plan.cases.filter(c => c.partition === partition)) {
      const a = rows.find(r => r.caseId === sample.id && r.route === baseline);
      const b = rows.find(r => r.caseId === sample.id && r.route === candidate);
      const reason = !a || !b ? 'MISSING_RUN' : a.status !== 'SUCCEEDED' || b.status !== 'SUCCEEDED' ? 'RUN_FAILED'
        : hash(a.config) !== hash(b.config) || a.initialKnowledgeExcerpts !== b.initialKnowledgeExcerpts ? 'CONFIG_MISMATCH'
          : !scored.has(a.runId) || !scored.has(b.runId) ? 'UNSCORED' : null;
      if (reason) { excluded.push({ caseId: sample.id, reason }); continue; }
      const sa = scored.get(a.runId), sb = scored.get(b.runId);
      pairs.push({ caseId: sample.id, delta: Object.fromEntries(dimensions.map(d => [d, sb[d] - sa[d]])),
        unsupportedCorrection: { baseline: sa.unsupportedCorrection, candidate: sb.unsupportedCorrection },
        repeatedQuestion: { baseline: sa.repeatedQuestion, candidate: sb.repeatedQuestion } });
    }
    comparisons.push({ partition, baseline, candidate, pairedCases: pairs.length,
      meanDelta: Object.fromEntries(dimensions.map(d => [d, pairs.length ? pairs.reduce((sum, p) => sum + p.delta[d], 0) / pairs.length : null])),
      pairs, excluded });
  }
  const complete = groups.every(g => g.observed === g.expected && g.succeeded === g.expected && g.reviewed === g.expected)
    && comparisons.every(c => c.excluded.length === 0);
  return { version: 'agent-quality-summary-v1', expectedRuns: plan.expectedRuns, observedRuns: rows.length,
    humanReviewedRuns: scored.size, groups, comparisons,
    reviewStatus: complete ? 'COMPLETE_DESCRIPTIVE_ONLY' : 'INCOMPLETE_NO_QUALITY_CLAIM',
    qualityImprovementClaim: 'NOT_ESTABLISHED',
    limitations: ['Human identity/blindness is a reviewer declaration, not independently authenticated.',
      'Failed and missing runs remain in coverage; paired means condition on successful comparable reviewed pairs.',
      '30 synthetic single-turn families do not establish production or heldout quality.',
      'Development scores are diagnostic; validation results must be reported separately.',
      'This offline tool makes zero provider calls and never invents ratings.'] };
}

async function localPath(path) {
  const absolute = resolve(root, path), rel = relative(root, absolute);
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) fail('Path must be inside this project');
  let current = root;
  for (const part of rel.split(sep)) {
    current = resolve(current, part);
    try { if ((await lstat(current)).isSymbolicLink()) fail('Symbolic links are not allowed'); }
    catch (e) { if (e.code !== 'ENOENT') throw e; }
  }
  return absolute;
}
const readJson = async path => JSON.parse(await readFile(await localPath(path), 'utf8'));
const writeNew = async (path, value) => writeFile(await localPath(path), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });

async function main(args) {
  const [action, directory, ...inputs] = args;
  if (action === 'prepare' && directory && inputs.length) {
    const plan = makePlan(await readJson('backend/src/test/resources/agent-evaluation.json'));
    const evidence = [];
    for (const path of inputs) {
      const bytes = await readFile(await localPath(path), 'utf8');
      evidence.push({ path, sha256: hash(bytes), rows: bytes.trim().split('\n').filter(Boolean).map(JSON.parse) });
    }
    const rows = evidence.flatMap(e => e.rows), prepared = prepareReview(plan, rows);
    const summary = summarizeReview(plan, rows, prepared.key, prepared.review);
    await mkdir(await localPath(directory)); // New directory required; existing human work is never overwritten.
    for (const [name, value] of Object.entries({ plan, evidence, key: prepared.key, 'blind-review': prepared.review, summary }))
      await writeNew(`${directory}/${name}.json`, value);
    console.log(JSON.stringify({ directory, expectedRuns: plan.expectedRuns, observedRuns: rows.length,
      humanReviewedRuns: 0, status: summary.reviewStatus, providerCalls: 0 }));
  } else if (action === 'summarize' && directory && inputs.length === 1) {
    const plan = await readJson(`${directory}/plan.json`), evidence = await readJson(`${directory}/evidence.json`);
    for (const source of evidence) {
      const bytes = await readFile(await localPath(source.path), 'utf8');
      if (hash(bytes) !== source.sha256 || canonical(bytes.trim().split('\n').filter(Boolean).map(JSON.parse)) !== canonical(source.rows))
        fail('Original evidence changed');
    }
    if (canonical(makePlan(await readJson('backend/src/test/resources/agent-evaluation.json'))) !== canonical(plan)) fail('Frozen plan changed');
    const result = summarizeReview(plan, evidence.flatMap(e => e.rows), await readJson(`${directory}/key.json`),
      await readJson(`${directory}/blind-review.json`));
    await writeNew(inputs[0], result);
    console.log(JSON.stringify({ output: inputs[0], status: result.reviewStatus, humanReviewedRuns: result.humanReviewedRuns }));
  } else fail('Usage: node scripts/agent-quality-review.mjs prepare NEW_PROJECT_DIR REPORT.jsonl... | summarize PACK_DIR NEW_SUMMARY.json');
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main(process.argv.slice(2));
