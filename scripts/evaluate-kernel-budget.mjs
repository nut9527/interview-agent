// 显式运行的评估批次：仅使用合成的开发与验证数据，并持久化保守费用预占记录。
import { readFile, writeFile, access, open, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { parseEnv, capture } from './dev.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
if (process.argv[2] === '--closeout') {
  await (await import('./evaluate-closeout.mjs')).closeoutMain(process.argv.slice(3));
} else {
const policy = process.argv[2];
if (!['B0', 'S1', 'S3'].includes(policy)) throw new Error('Usage: node scripts/evaluate-kernel-budget.mjs B0|S1|S3');
const directory = join(root, 'docs/evidence'), ledgerPath = join(directory, 'kernel-model-budget.json');
// 以独占文件锁串行化台账更新，防止并行评估重复使用同一剩余额度。
const lockPath=ledgerPath+'.lock', lock=await open(lockPath,'wx');
try {
const selected=process.argv[3]||'all';
if(!['all','redis-order','adequate-owner'].includes(selected)) throw new Error('Unsupported development case');
const ids = selected==='all'?['redis-order', 'adequate-owner']:[selected];
const data = JSON.parse(await readFile(join(root, 'backend/src/test/resources/agent-evaluation.json'), 'utf8'));
if (!ids.every(id => data.some(f => f.id === id && ['development', 'validation'].includes(f.partition)))) throw new Error('Only non-heldout fixtures allowed');
const settings = parseEnv(await readFile(join(root, '.env'), 'utf8'));
if (settings.DEEPSEEK_BASE_URL && settings.DEEPSEEK_BASE_URL.replace(/\/$/, '') !== 'https://api.deepseek.com') throw new Error('Campaign price book applies only to the official DeepSeek endpoint');
if (!(settings.DEEPSEEK_API_KEY || settings.AI_API_KEY)) throw new Error('Missing model credentials');
const price = { currency: 'CNY', date: '2026-10-31', source: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/', model: 'deepseek-flash', inputMicroCnyPerToken: 2, outputMicroCnyPerToken: 8, basis: 'peak price, all input billed as cache miss' };
// 以 UTF-8 字节数估计令牌数上界；生产适配器会拒绝超过 160,000 个 UTF-16 代码单元的 JSON 请求体。
const perAttempt = 480000 * price.inputMicroCnyPerToken + 3000 * price.outputMicroCnyPerToken;
const reservation = ids.length * 4 * perAttempt;
let ledger;
try { ledger = JSON.parse(await readFile(ledgerPath, 'utf8')); }
catch (e) { if (e.code !== 'ENOENT') throw e; ledger = { campaign: 'kernel-development', authorizedCapMicroCny: 10000000, price, experiments: [], heldoutExecuted: false }; }
if (ledger.authorizedCapMicroCny !== 10000000 || JSON.stringify(ledger.price) !== JSON.stringify(price)) throw new Error('Budget campaign configuration changed');
const candidateDigest = createHash('sha256');
const runtimeSources = ['InterviewRunWorker', 'InterviewContextStage', 'InterviewModelStage', 'InterviewToolStage',
  'InterviewValidateStage', 'RunEvidence', 'BudgetProfile', 'RunAttemptLedger'];
for (const file of [...runtimeSources.map(name => `interview/execution/${name}.java`),
  'interview/InterviewRunModel.java', 'common/execution/AgentExecution.java']) {
  candidateDigest.update(file).update('\0').update(await readFile(join(root, 'backend/src/main/java/cn/edu/whu/zhizhida', file)));
}
const candidateHash = candidateDigest.digest('hex');
if (ledger.experiments.some(e => e.policy === policy && e.candidateHash===candidateHash && JSON.stringify(e.ids)===JSON.stringify(ids) && e.state!=='PRE_MODEL_FAILURE')) throw new Error('This policy/candidate/case set is already reserved or executed; review its existing evidence before starting another campaign');
const spent = ledger.experiments.reduce((sum, e) => sum + e.retainedMicroCny, 0);
if (spent + reservation > ledger.authorizedCapMicroCny) throw new Error('Conservative remaining budget cannot admit this experiment');
const ordinal=ledger.experiments.filter(e=>e.policy===policy).length+1;
const reportName = `kernel-real-${policy.toLowerCase()}-${ordinal}.jsonl`, reportPath = join(directory, reportName);
try { await access(reportPath); throw new Error('Refusing to overwrite existing evaluation evidence'); } catch (e) { if (e.code !== 'ENOENT') throw e; }
const experiment = { policy, ids, candidateHash, state: 'RESERVED', retainedMicroCny: reservation, maximumAttempts: ids.length*4, report: reportName };
// 调用模型前先落盘预占最大费用；进程中断也不能使已准入的调用脱离预算。
ledger.experiments.push(experiment);
await writeFile(ledgerPath, JSON.stringify(ledger, null, 2) + '\n');
const env = { ...process.env, DEEPSEEK_MODEL: price.model, DEEPSEEK_THINKING: 'disabled', INTERVIEW_RUNS_LOOP_POLICY: policy,
  INTERVIEW_RUNS_DECISION_PROTOCOL: 'phased-v2', SPRING_PROFILES_ACTIVE: 'agent-evaluation',
  RUN_EVAL_IDS: ids.join(','), RUN_EVAL_REPORT: reportName, MAVEN_ARGS: '-Dspring-boot.repackage.skip=true',
  MAVEN_OPTS: `${process.env.MAVEN_OPTS || ''} -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE` };
console.log(JSON.stringify({ policy, cases: ids.length, maximumCalls: ids.length*4, reservationCny: reservation / 1000000, priorRetainedCny: spent / 1000000 }));
try {
  const result = await capture(process.execPath, [resolve(root, 'scripts/evaluate-agent-runs.mjs'), 'TOOL'], { cwd: root, env, timeout: 300000 });
  if(result.code!==0) console.log(result.output.slice(-1500));
  const rows = (await readFile(reportPath, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
  if (rows.length !== ids.length || rows.some(r => r.config.loopPolicy !== policy || r.config.model.model !== price.model)) throw new Error('Unexpected experiment policy or model');
  let retained = 0, unknown = 0, attempts = 0, reportedInput = 0, reportedOutput = 0;
  for (const row of rows) for (const attempt of row.attemptLedger) {
    attempts++;
    // 仅以有效且未越界的供应商用量结算；用量未知时保留本次调用的最大预占。
    const usage = attempt.usage_json ? JSON.parse(attempt.usage_json) : null;
    if (usage?.status === 'REPORTED' && Number.isSafeInteger(usage.inputTokens) && Number.isSafeInteger(usage.outputTokens)
        && usage.inputTokens >= 0 && usage.outputTokens >= 0 && usage.inputTokens <= 480000 && usage.outputTokens <= 3000) {
      reportedInput += usage.inputTokens; reportedOutput += usage.outputTokens;
      retained += usage.inputTokens * price.inputMicroCnyPerToken + usage.outputTokens * price.outputMicroCnyPerToken;
    } else { unknown++; retained += perAttempt; }
  }
  if (attempts > experiment.maximumAttempts || retained > reservation) throw new Error('Attempt or reservation invariant failed');
  Object.assign(experiment, { state: result.code === 0 ? 'RECORDED' : 'FAILED_WITH_EVIDENCE', retainedMicroCny: retained, attempts, unknownAttempts: unknown, reportedInputTokens: reportedInput, reportedOutputTokens: reportedOutput,
    cases: rows.map(r => ({ id: r.caseId, status: r.status, error: r.error, elapsedMs: r.elapsedMs, attempts: r.attempts, tools: r.toolExecutions })), humanReview: 'PENDING' });
  await writeFile(ledgerPath, JSON.stringify(ledger, null, 2) + '\n');
  console.log(JSON.stringify(experiment));
  if (result.code !== 0) { console.log(result.output.slice(-1500)); process.exitCode = 1; }
} catch (error) {
  experiment.state = 'UNSETTLED'; // 无法恢复评估证据时，继续保留全额费用预占。
  try {
    const audit=JSON.parse(await readFile(reportPath+'.audit.json','utf8'));
    if(audit.totalAttempts===0) Object.assign(experiment,{state:'PRE_MODEL_FAILURE',retainedMicroCny:0,attempts:0,audit:reportName+'.audit.json'});
  } catch { /* 缺少审计记录时不释放预占额度。 */ }
  await writeFile(ledgerPath, JSON.stringify(ledger, null, 2) + '\n');
  console.error(error.message); process.exitCode = 1;
}
} finally { await lock.close(); await rm(lockPath); }
}
