// 复用既有真实 Worker 评测入口；新账本、新候选及逐请求预占与旧实验分开。
import { readFile, writeFile, mkdir, lstat, open } from 'node:fs/promises';
import { join, resolve, relative, isAbsolute } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { projectRoot, verifyCandidate, listPayload, sha256, jsonBytes } from './release-candidate.mjs';
import { canonical, checkFixtures } from './release-fixtures.mjs';
import { openBudget, budgetProxy, validatePrice, reservationFor } from './closeout-budget.mjs';
import { parseEnv, capture } from './dev.mjs';
import { reviewTemplate, verifyReviewEvidence, assessReview } from './closeout-review.mjs';

const planHash = plan => sha256(canonical(plan));
const ledgerPath = join(projectRoot, 'docs/evidence/closeout-model-budget.jsonl');

async function localPath(path) {
  const absolute = resolve(projectRoot, path), rel = relative(projectRoot, absolute);
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) throw new Error('Path must stay inside this project');
  let current = projectRoot;
  for (const part of rel.split(/[\\/]/)) {
    current = join(current, part);
    try { if ((await lstat(current)).isSymbolicLink()) throw new Error('Links are not allowed'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return absolute;
}

export async function prepare(candidateId) {
  if (!/^[a-z0-9][a-z0-9-]{0,79}$/.test(candidateId || '')) throw new Error('Invalid candidate ID');
  const candidatePath = `outputs/release/${candidateId}`;
  const source = await verifyCandidate(await localPath(candidatePath));
  const registration = await checkFixtures();
  const config = JSON.parse(await readFile(join(projectRoot, 'contracts/release-v1.0.json'), 'utf8'));
  const price = JSON.parse(await readFile(join(projectRoot, 'contracts/closeout-price-v1.json'), 'utf8'));
  validatePrice(price);
  const batch = config.paidExecution.batches[0];
  const plan = { schemaVersion: 'closeout-batch-v1', campaign: 'v1-closeout', batchId: batch.id,
    authorization: 'NOT_GRANTED', candidatePath, sourceManifestSha256: source.manifestSha256,
    registrationSha256: registration.configSha256, price,
    samples: batch.sampleOrder.map(id => config.samples.find(sample => sample.id === id)),
    required: batch.required, maximumMicroCny: batch.maximumMicroCny, maximumAttempts: batch.maximumAttempts,
    wallClockMs: 300000, maximumRequestBytes: 480000, maximumOutputTokens: 2000,
    unknownUsage: 'RETAIN_RESERVATION', settlement: 'CONSERVATIVE_PEAK_CACHE_MISS',
    scope: '第一批两个固定短场景；后两批须完成 HTTP 流程接入和人工检查门禁后另行冻结',
    acceptance: '执行结束只可为 NOT_STARTED、FAIL 或 REVIEW_PENDING；需逐项人工检查才可判 PASS' };
  const directory = await localPath('outputs/closeout'); await mkdir(directory, { recursive: true });
  const path = join(directory, `${candidateId}-batch1.json`);
  await writeFile(path, jsonBytes(plan), { flag: 'wx' });
  return { path, planSha256: planHash(plan), maximumCny: 6, maximumAttempts: 8, authorization: plan.authorization };
}

export async function preflight(path) {
  const plan = JSON.parse(await readFile(await localPath(path), 'utf8'));
  if (plan.schemaVersion !== 'closeout-batch-v1' || plan.campaign !== 'v1-closeout' || plan.batchId !== 'closeout-1'
      || plan.authorization !== 'NOT_GRANTED' || plan.maximumMicroCny !== 6000000 || plan.maximumAttempts !== 8
      || plan.wallClockMs !== 300000 || plan.maximumRequestBytes !== 480000 || plan.maximumOutputTokens !== 2000)
    throw new Error('Only the frozen first batch is currently executable');
  if (!/^outputs\/release\/[a-z0-9][a-z0-9-]{0,79}$/.test(plan.candidatePath)) throw new Error('Invalid source candidate path');
  const directory = await localPath(plan.candidatePath);
  const verified = await verifyCandidate(directory);
  const registered = await checkFixtures();
  if (verified.manifestSha256 !== plan.sourceManifestSha256 || registered.configSha256 !== plan.registrationSha256)
    throw new Error('Candidate or registered inputs changed');
  const config = JSON.parse(await readFile(join(projectRoot, 'contracts/release-v1.0.json'), 'utf8'));
  const batch = config.paidExecution.batches[0];
  if (canonical(plan.samples) !== canonical(batch.sampleOrder.map(id => config.samples.find(row => row.id === id)))
      || canonical(plan.required) !== canonical(batch.required)) throw new Error('Batch order, inputs or acceptance criteria changed');
  // 既有评测器在工作区编译。逐文件确认与获准候选完全一致，避免把旧指纹标到新代码上。
  const inventory = JSON.parse(await readFile(join(directory, 'FILES.json'), 'utf8'));
  if (canonical(await listPayload()) !== canonical(inventory.map(row => row.path))) throw new Error('Workspace file set differs from frozen candidate');
  for (const row of inventory) {
    const current = await readFile(join(projectRoot, row.path));
    const frozen = await readFile(join(directory, 'payload', row.path));
    if (!current.equals(frozen)) throw new Error(`Workspace differs from frozen candidate: ${row.path}`);
  }
  validatePrice(plan.price);
  const largest = reservationFor(Buffer.from(JSON.stringify({ model: plan.price.model, thinking: { type: 'disabled' },
    stream: false, max_tokens: 2000, messages: [{ role: 'user', content: '预检' }] })), plan.price);
  return { plan, sha256: planHash(plan), maximumReservationMicroCny: largest.microCny };
}

// 禁止环境中的实验开关和真实密钥进入评测子进程；它只持有本机闸门令牌。
export function evaluationEnv(base, url, token, report) {
  const env = Object.fromEntries(Object.entries(base).filter(([key]) =>
    !/^(?:AI_|DEEPSEEK_|INTERVIEW_|AGENT_|LEARNING_|REDIS_|RABBITMQ_|APP_|RUN_EVAL_|RUN_REAL_|SPRING_|MAVEN_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)/i.test(key)));
  return { ...env, DEEPSEEK_API_KEY: token, AI_API_KEY: token, DEEPSEEK_BASE_URL: url,
    DEEPSEEK_MODEL: 'deepseek-flash', DEEPSEEK_THINKING: 'disabled', DEEPSEEK_TIMEOUT_SECONDS: '30',
    INTERVIEW_RUNS_LOOP_POLICY: 'B0',
    INTERVIEW_RUNS_DECISION_PROTOCOL: 'legacy', INTERVIEW_ADAPTIVE_ROUTING_ENABLED: 'false',
    LEARNING_PRACTICE_TOOLS_ENABLED: 'false', AGENT_VECTOR_ENABLED: 'false',
    AGENT_TRACE_EXPORT_ENABLED: 'false', REDIS_HEALTH_ENABLED: 'false', RABBITMQ_HEALTH_ENABLED: 'false',
    REDIS_RATE_LIMIT_ENABLED: 'false', QUESTION_CACHE_ENABLED: 'false', RUN_DISPATCH_MODE: 'database',
    KNOWLEDGE_DISPATCH_MODE: 'local',
    RUN_EVAL_IDS: 'redis-order,adequate-owner', RUN_EVAL_REPORT: report,
    CLOSEOUT_BUDGET_PROXY: 'true', MAVEN_ARGS: '-Dspring-boot.repackage.skip=true' };
}

export function providerKey(settings) {
  if (settings.DEEPSEEK_BASE_URL && settings.DEEPSEEK_BASE_URL.replace(/\/+$/, '') !== 'https://api.deepseek.com')
    throw new Error('Configured provider differs from the frozen official endpoint');
  const key = settings.DEEPSEEK_API_KEY || settings.AI_API_KEY;
  if (!key) throw new Error('Missing credentials');
  return key;
}

export async function run(path, approval) {
  const checked = await preflight(path), plan = checked.plan;
  if (approval !== `--approve=${checked.sha256}`) throw new Error('Explicit approval of this exact plan hash is required');
  // 此处只是授权落地标记；操作者必须先得到预算授权，不由程序推断用户同意。
  const settings = parseEnv(await readFile(join(projectRoot, '.env'), 'utf8'));
  const key = providerKey(settings);
  // 只读可用性检查在模型请求及批次记账之前，失败不消耗付费批次。
  const available = await fetch('https://api.deepseek.com/models', { redirect: 'error',
    headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(10000) });
  if (!available.ok || !(await available.json()).data?.some(model => model.id === plan.price.model))
    throw new Error('Frozen model is unavailable to this account');
  const prefix = `closeout-1-${checked.sha256.slice(0, 16)}`, report = prefix + '.jsonl';
  for (const suffix of ['.jsonl', '.jsonl.audit.json', '.wire.jsonl']) {
    try { await lstat(join(projectRoot, 'docs/evidence', prefix + suffix)); throw new Error('Refusing to overwrite batch evidence'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const budget = await openBudget(await localPath(ledgerPath));
  let proxy, wire, begun = false;
  try {
    wire = await open(join(projectRoot, 'docs/evidence', prefix + '.wire.jsonl'), 'wx');
    const record = async row => { await wire.writeFile(JSON.stringify({ planSha256: checked.sha256,
      sourceManifestSha256: plan.sourceManifestSha256, ...row }) + '\n'); await wire.sync(); };
    await budget.begin({ ...plan, sha256: checked.sha256 }); begun = true;
    const token = randomBytes(32).toString('hex');
    proxy = await budgetProxy(budget, token, async (body, attemptId) => {
      const requestSha256 = sha256(body);
      await record({ kind: 'REQUEST', attemptId, requestSha256, body: body.toString('utf8') });
      // 重定向不跟随；请求和完整响应共用 35 秒期限，无隐式重试。
      const response = await fetch(plan.price.endpoint, { method: 'POST', redirect: 'error',
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }, body,
        signal: AbortSignal.timeout(35000) });
      const chunks = []; let bytes = 0;
      for await (const chunk of response.body) {
        bytes += chunk.length;
        if (bytes > 1048576) throw new Error('Provider response exceeds byte limit');
        chunks.push(chunk);
      }
      const result = { status: response.status, body: Buffer.concat(chunks) };
      await record({ kind: 'RESPONSE', attemptId, requestSha256, status: result.status, body: result.body.toString('utf8') });
      return result;
    });
    const result = await capture(process.execPath, [join(projectRoot, 'scripts/evaluate-agent-runs.mjs'), 'TOOL'],
      { cwd: projectRoot, env: evaluationEnv(process.env, proxy.url, token, report), timeout: 300000 });
    let accepted = false;
    try {
      const rows = (await readFile(join(projectRoot, 'docs/evidence', report), 'utf8')).trim().split('\n').map(JSON.parse);
      accepted = result.code === 0 && canonical(rows.map(row => row.caseId)) === canonical(plan.samples.map(row => row.id))
        && rows.every(row => row.status === 'SUCCEEDED' && row.config.loopPolicy === 'B0'
          && row.config.model.model === plan.price.model)
        && rows[0].toolExecutions > 0 && rows[0].receipts.length > 0;
    } catch { /* 缺少结果或不完整结果不能判作通过。 */ }
    await proxy.close(); proxy = null;
    await wire.sync();
    const evidence = accepted ? {
      reportPath: `docs/evidence/${report}`, wirePath: `docs/evidence/${prefix}.wire.jsonl`,
      reportSha256: sha256(await readFile(join(projectRoot, 'docs/evidence', report))),
      wireSha256: sha256(await readFile(join(projectRoot, 'docs/evidence', prefix + '.wire.jsonl'))),
    } : undefined;
    await budget.finish(accepted ? 'REVIEW_PENDING' : 'FAIL', evidence); begun = false;
    console.log(JSON.stringify({ status: budget.snapshot().batches.at(-1).status, planSha256: checked.sha256, report,
      note: '自动检查不代替人工质量检查；未知用量保留预占。' }));
    if (!accepted) process.exitCode = 1;
  } finally {
    try { await proxy?.close(); if (begun) await budget.finish('FAIL'); }
    finally { try { await wire?.close(); } finally { await budget.close(); } }
  }
}

// 检查历史批次不重新核价或要求工作区不变；以账本中的获准计划和已冻结原始证据为准。
export async function reviewBatch(command, planPath, reviewPath) {
  const plan = JSON.parse(await readFile(await localPath(planPath), 'utf8'));
  // 避免错误路径创建空账本；历史证据只读，检查表以新文件保存。
  await lstat(await localPath(ledgerPath));
  const budget = await openBudget(await localPath(ledgerPath));
  try {
    const batch = budget.snapshot().batches.at(-1);
    if (!batch?.evidence) throw new Error('No frozen evidence to review');
    const reportBytes = await readFile(await localPath(batch.evidence.reportPath));
    const wireBytes = await readFile(await localPath(batch.evidence.wirePath));
    const report = verifyReviewEvidence(plan, batch, reportBytes, wireBytes);
    const destination = await localPath(reviewPath);
    if (command === 'review-prepare') {
      await writeFile(destination, jsonBytes(reviewTemplate(plan, batch, report)), { flag: 'wx' });
      return { status: 'REVIEW_PENDING', path: destination, note: '逐项填写后先 review-check，再 review-apply；不自动判 PASS。' };
    }
    const document = JSON.parse(await readFile(destination, 'utf8'));
    return command === 'review-check' ? assessReview(plan, batch, document, reportBytes, wireBytes)
      : await budget.review(plan, document, reportBytes, wireBytes);
  } finally { await budget.close(); }
}

export async function closeoutMain(args) {
  const [command, path, approval] = args;
  if (command === 'prepare' && path && !approval) console.log(await prepare(path));
  else if (command === 'check' && path && !approval) {
    const result = await preflight(path);
    console.log({ planSha256: result.sha256, maximumReservationCny: result.maximumReservationMicroCny / 1000000,
      maximumBatchCny: 6, maximumAttempts: 8, authorization: 'NOT_GRANTED', upstreamRequests: 0 });
  } else if (command === 'run' && path && approval) await run(path, approval);
  else if (['review-prepare', 'review-check', 'review-apply'].includes(command) && path && approval)
    console.log(await reviewBatch(command, path, approval));
  else throw new Error('Usage: --closeout prepare <candidate-id> | check <plan-path> | run <plan-path> --approve=<plan-sha256> | review-prepare|review-check|review-apply <plan-path> <review-path>');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await closeoutMain(process.argv.slice(2));
