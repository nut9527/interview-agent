import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { PROJECT_ROOT, parseEnv, capture, findExecutable } from './dev.mjs';
import { checkWorkerEvidence, verifiedKnowledgeCitations } from './agent-evaluation-metrics.mjs';

const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('node scripts/verify-langchain4j-provider.mjs --real --max-requests=8 --max-output-tokens=24000 [--windows-root]');
  console.log('Two synthetic cases through the durable Worker and disposable MySQL. Each case requires four requests of remaining budget before it starts; caps below eight may stop before case two.');
  console.log('With --retrieval-probe, choose 1–4 requests and 3000 output tokens per request to run redis-order with initial document excerpts withheld.');
} else {
  const requests = Number(args.find(arg => arg.startsWith('--max-requests='))?.split('=')[1]);
  const outputTokens = Number(args.find(arg => arg.startsWith('--max-output-tokens='))?.split('=')[1]);
  const required = ['--real', `--max-requests=${requests}`, `--max-output-tokens=${outputTokens}`];
  const retrievalProbe = args.includes('--retrieval-probe');
  if (!required.every(arg => args.includes(arg)) || args.some(arg => ![...required, '--windows-root', '--retrieval-probe'].includes(arg))
      || new Set(args).size !== args.length || !Number.isInteger(requests)
      || requests < (retrievalProbe ? 1 : 4) || requests > (retrievalProbe ? 4 : 8) || outputTokens !== requests * 3000)
    throw new Error('Use --help for the bounded real-provider command.');
  const local = parseEnv(await readFile(join(PROJECT_ROOT, '.env'), 'utf8'));
  const config = { ...local, ...process.env };
  const key = config.AI_API_KEY || config.DEEPSEEK_API_KEY;
  if (!key) throw new Error('Missing provider key.');
  const endpoint = config.DEEPSEEK_BASE_URL || 'https://api.deepseek.com';
  const url = new URL(endpoint);
  if (url.origin !== 'https://api.deepseek.com' || url.username || url.password || url.search || url.hash
      || !['/', '/v1', '/v1/'].includes(url.pathname)) throw new Error('Official DeepSeek endpoint required.');
  if (args.includes('--windows-root') && process.platform !== 'win32') throw new Error('--windows-root requires Windows.');
  const model = config.DEEPSEEK_MODEL || 'deepseek-flash';
  const tlsArgs = args.includes('--windows-root')
    ? ['-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-Djavax.net.ssl.trustStore=NONE'] : [];
  const preflight = await capture(findExecutable('mvn'), ['-o', '-B', '-ntp',
    `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`, '-Djacoco.skip=true', ...tlsArgs,
    '-Dtest=ProviderModelPreflightTest#configuredModelMustBeListedBeforeInference', 'test'], {
      cwd: join(PROJECT_ROOT, 'backend'), timeout: 120000,
      env: { ...process.env, RUN_PROVIDER_PREFLIGHT: 'true', AI_API_KEY: key,
        ACCEPTANCE_BASE_URL: endpoint, ACCEPTANCE_MODEL: model }
    });
  if (preflight.code !== 0) throw new Error('Read-only provider preflight failed; no inference requests sent.');
  const batch = randomUUID();
  const cases = retrievalProbe ? ['redis-order'] : ['redis-order', 'adequate-owner'];
  const caseLimit = retrievalProbe ? requests : 4;
  const registration = JSON.parse(await readFile(join(PROJECT_ROOT, 'contracts/release-v1.0.json'), 'utf8'));
  const summary = { model, maxRequests: requests, maxOutputTokens: outputTokens,
    cases, initialKnowledgeExcerpts: retrievalProbe ? 'WITHHELD' : 'FIXTURE', runs: [], result: 'RUNNING' };
  const destination = join(PROJECT_ROOT, `docs/evidence/langchain4j-worker-${batch}.json`);
  const save = () => writeFile(destination, JSON.stringify(summary, null, 2) + '\n');
  await writeFile(destination, JSON.stringify(summary, null, 2) + '\n', { flag: 'wx' });
  try {
    for (const caseId of cases) {
      if (summary.runs.reduce((sum, run) => sum + run.retainedRequests, 0) + caseLimit > requests)
        throw new Error('BUDGET_EXHAUSTED');
      const report = `langchain4j-worker-${batch}-${caseId}.jsonl`;
      const run = { caseId, report, retainedRequests: caseLimit, status: 'RUNNING' };
      summary.runs.push(run);
      await save();
      const env = { ...process.env, AI_API_KEY: key, DEEPSEEK_API_KEY: key, DEEPSEEK_BASE_URL: endpoint,
        SPRING_PROFILES_ACTIVE: 'agent-evaluation', DEEPSEEK_MODEL: model, DEEPSEEK_THINKING: 'disabled', RUN_EVAL_IDS: caseId, RUN_EVAL_REPORT: report,
        RUN_EVAL_MAX_ATTEMPTS: String(caseLimit), RUN_EVAL_WITHHOLD_EXCERPTS: String(retrievalProbe),
        MAVEN_ARGS: '-Dinterview.runs.loop-policy=B0 -Dinterview.runs.decision-protocol=phased-v2'
          + (args.includes('--windows-root') ? ' -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE' : ''),
        MAVEN_OPTS: '' };
      console.log(`[worker-provider] ${caseId}: at most ${caseLimit} requests / ${caseLimit * 3000} output tokens.`);
      try {
        const result = await capture(process.execPath, [join(PROJECT_ROOT, 'scripts/evaluate-agent-runs.mjs'), 'TOOL'],
          { cwd: PROJECT_ROOT, env, timeout: 300000 });
        run.exitCode = result.code;
        const rows = (await readFile(join(PROJECT_ROOT, 'docs/evidence', report), 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);
        const row = rows[0];
        if (rows.length !== 1 || row.caseId !== caseId
            || row.config.model.protocolVersion !== 'deepseek-langchain4j-v1' || row.config.model.model !== model
            || row.config.loopPolicy !== 'B0' || row.attempts > caseLimit || row.attemptLedger.length !== row.attempts
            || (row.initialKnowledgeExcerpts === 'WITHHELD') !== retrievalProbe)
          throw new Error('UNEXPECTED_WORKER_RESULT');
        Object.assign(run, { sourceHash: row.sourceHash, status: row.status,
          error: row.error, attempts: row.attempts, tools: row.toolExecutions, elapsedMs: row.elapsedMs,
          modelToolRequests: row.modelToolRequests.length,
          verifiedKnowledgeCitations: verifiedKnowledgeCitations(row),
          toolReceipts: row.receipts.map(receipt => ({ name: receipt.tool_name, kind: receipt.execution_kind })),
          evidenceCheck: checkWorkerEvidence(row, registration.samples.find(sample => sample.id === caseId)),
          usage: row.attemptLedger.map(attempt => attempt.usage_json ? JSON.parse(attempt.usage_json) : null) });
        console.log(`[worker-provider] ${caseId}: ${run.status}, attempts=${run.attempts}, tools=${run.tools}`);
      } finally {
        if (run.status === 'RUNNING') run.status = 'INCOMPLETE';
        const audit = await readFile(join(PROJECT_ROOT, 'docs/evidence', report + '.audit.json'), 'utf8').then(JSON.parse).catch(() => null);
        if (Number.isInteger(audit?.totalAttempts) && audit.totalAttempts >= 0 && audit.totalAttempts <= caseLimit)
          run.retainedRequests = audit.totalAttempts;
        await save();
      }
    }
    summary.result = summary.runs.every(run => run.exitCode === 0 && run.evidenceCheck === 'PASSED')
      ? 'PASSED' : 'FAILED';
    if (summary.result !== 'PASSED') process.exitCode = 1;
  } catch (error) {
    summary.result = 'INCOMPLETE';
    summary.error = ['BUDGET_EXHAUSTED', 'UNEXPECTED_WORKER_RESULT'].includes(error.message)
      ? error.message : 'EVALUATION_INTERRUPTED';
    process.exitCode = 1;
    console.error('Worker verification incomplete; inspect the generated reports.');
  } finally {
    await save();
    console.log(`[worker-provider] ${summary.result}; ${destination}`);
  }
}
