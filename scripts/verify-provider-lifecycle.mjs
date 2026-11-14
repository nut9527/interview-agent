import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { PROJECT_ROOT, parseEnv, capture, findExecutable } from './dev.mjs';
import { verifyBackend } from './verify-backend.mjs';

const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('Prepare without inference: node scripts/verify-provider-lifecycle.mjs --prepare');
  console.log('Explicit paid run: node scripts/verify-provider-lifecycle.mjs --real --stage=phase-1 --max-requests=8 --max-output-tokens=16000');
  console.log('Synthetic production Worker lifecycle; isolated MySQL/Redis; no automatic paid retry. Each stage is new evidence under backend/target/provider-simplified.');
  process.exit(0);
}
const prepare = args.length === 1 && args[0] === '--prepare';
const value = name => args.find(arg => arg.startsWith(`--${name}=`))?.slice(name.length + 3);
const stage = value('stage');
const maxRequests = Number(value('max-requests'));
const maxOutputTokens = Number(value('max-output-tokens'));
if (!prepare && (args.length !== 4 || !args.includes('--real') || !/^[a-z0-9][a-z0-9-]{0,39}$/.test(stage || '')
    || !Number.isInteger(maxRequests) || maxRequests < 1 || maxRequests > 8 || maxOutputTokens !== maxRequests * 2000)) {
  throw new Error('Use --prepare or explicit --real --stage=<new-name> --max-requests=1..8 --max-output-tokens=<requests*2000>.');
}
const settings = { ...parseEnv(await readFile(join(PROJECT_ROOT, '.env'), 'utf8')), ...process.env };
const key = settings.AI_API_KEY || settings.DEEPSEEK_API_KEY;
const endpoint = settings.DEEPSEEK_BASE_URL || 'https://api.deepseek.com';
const model = settings.DEEPSEEK_MODEL || 'deepseek-flash';
const uri = new URL(endpoint);
if (uri.origin !== 'https://api.deepseek.com' || uri.username || uri.password || uri.search || uri.hash)
  throw new Error('This acceptance fixture requires the official DeepSeek HTTPS endpoint.');
const docker = findExecutable('docker');
for (const tool of ['mvn', 'mysqld', 'mysql', 'mysqladmin']) findExecutable(tool);
const redisImage = await capture(docker, ['image', 'inspect', 'redis:7.4.2', '--format', '{{.Id}}'], { timeout: 10000 });
if (redisImage.code !== 0) throw new Error('Cached redis:7.4.2 image is required; this runner does not pull images.');
if (prepare) {
  console.log(JSON.stringify({ result: 'PREPARED_NO_INFERENCE', hasKey: Boolean(key), endpoint, model,
    redisImage: redisImage.output.trim(), mysqlPort: 13327, reportRoot: 'backend/target/provider-simplified' }));
  process.exit(0);
}
if (!key) throw new Error('Provider key is missing; no request sent.');

const base = join(PROJECT_ROOT, 'backend/target/provider-simplified');
await mkdir(base, { recursive: true });
const directory = join(base, stage);
await mkdir(directory); // Existing evidence must never be overwritten by a rerun.
const build = join(directory, 'build');
const name = `provider-lifecycle-${randomUUID()}`;
const mavenArgs = ['-ntp', `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`,
  `-Dzhizhida.build.directory=${build}`, `-Dprovider.report-directory=${directory}`,
  '-Dtest=InterviewPromptTemplatesTest,InterviewPromptConfigurationTest,InterviewOutputContractTest,InterviewAgentGuardrailsTest',
  '-Dit.test=ProviderInterviewLifecycleIT', '-Djacoco.skip=true', '-DargLine=-Xmx512m',
  ...(process.platform === 'win32' ? ['-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-Djavax.net.ssl.trustStore=NONE'] : [])];
const execution = { scope: 'real-production-worker-synthetic-lifecycle', stage,
  result: 'INCOMPLETE', endpoint, model, maxRequests, maxOutputTokens, mysqlPort: 13327,
  reproduction: { command: `node scripts/verify-provider-lifecycle.mjs ${args.join(' ')}`,
    mavenGoals: ['verify'], mavenArgs: ['-o', '-B', ...mavenArgs], environmentVariableNames: [],
    injectedByDatabaseHarness: ['IT_DATABASE_URL', 'IT_DATABASE_USERNAME', 'IT_DATABASE_PASSWORD', 'TEMP', 'TMP'] },
  cleanup: { redisAndVolumesRemoved: false, disposableMysqlRemoved: false, verificationLogRemoved: false } };
let created = false;
try {
  const launched = await capture(docker, ['run', '-d', '--pull=never', '--name', name,
    '--label', `zhizhida.acceptance=${name}`, '-p', '127.0.0.1::6379', 'redis:7.4.2',
    'redis-server', '--save', '', '--appendonly', 'no'], { timeout: 60000 });
  if (launched.code !== 0) throw new Error('Owned Redis start failed.');
  created = true;
  const inspection = await capture(docker, ['inspect', name], { timeout: 10000 });
  const info = JSON.parse(inspection.output)[0];
  const port = info.NetworkSettings.Ports['6379/tcp'][0].HostPort;
  const ping = await capture(docker, ['exec', name, 'redis-cli', 'ping'], { timeout: 10000 });
  if (ping.code !== 0 || !ping.output.includes('PONG')) throw new Error('Owned Redis is not ready.');
  const env = { RUN_REAL_AGENT_EVAL: 'true', RUN_PROVIDER_LIFECYCLE: 'true', RUN_PROVIDER_ACCEPTANCE: 'false',
    RUN_PROVIDER_BUSINESS: 'false', RUN_PROVIDER_PREFLIGHT: 'false', AI_API_KEY: key, DEEPSEEK_API_KEY: key,
    ACCEPTANCE_BASE_URL: endpoint, ACCEPTANCE_MODEL: model, LIFECYCLE_REDIS_PORT: port, LIFECYCLE_REDIS_NAMESPACE: name,
    LIFECYCLE_MAX_REQUESTS: String(maxRequests), LIFECYCLE_MAX_OUTPUT_TOKENS: String(maxOutputTokens),
    IT_MYSQL_PORT: '13327', MAVEN_ARGS: '', MAVEN_OPTS: '-Xmx512m' };
  execution.reproduction.environmentVariableNames = Object.keys(env);
  Object.assign(process.env, env);
  const originalLog = console.log;
  console.log = (...values) => {
    const line = String(values[0]);
    if (line.startsWith('[integration]')) originalLog(...values);
    if (line.includes("Removed this run's MySQL data")) execution.cleanup.disposableMysqlRemoved = true;
  };
  try { await verifyBackend(['--offline'], mavenArgs); }
  finally { console.log = originalLog; }
  execution.result = 'PASSED';
} catch (error) {
  execution.result = 'FAILED';
  execution.failure = error.message.replaceAll(key, '[REDACTED]');
  process.exitCode = 1;
} finally {
  if (created) {
    const inspection = await capture(docker, ['inspect', name], { timeout: 10000 });
    const info = JSON.parse(inspection.output)[0];
    if (info.Config.Labels['zhizhida.acceptance'] !== name) throw new Error('Redis cleanup ownership mismatch.');
    execution.cleanup.redisAndVolumesRemoved = (await capture(docker, ['rm', '-f', '--volumes', name], { timeout: 60000 })).code === 0;
  }
  const rawFile = join(directory, 'provider-interview-lifecycle.json');
  const raw = await readFile(rawFile, 'utf8').catch(error => { if (error.code === 'ENOENT') return null; throw error; });
  if (raw) {
    const report = JSON.parse(raw);
    execution.evidenceSha256 = createHash('sha256').update(raw).digest('hex');
    execution.usage = report.runs.flatMap(run => run.modelTrace).reduce((total, attempt) => {
      const usage = attempt.usage || {};
      total.requests++;
      for (const field of ['inputTokens', 'outputTokens', 'totalTokens']) total[field] += usage[field] || 0;
      if (usage.status !== 'REPORTED') total.unknownAttempts++;
      return total;
    }, { requests: 0, inputTokens: 0, outputTokens: 0, totalTokens: 0, unknownAttempts: 0 });
    execution.reservedAttempts = report.totalAttempts;
    execution.semanticReview = 'PENDING: successful persistence alone does not establish semantic quality';
  }
  await rm(join(build, 'verification.log'), { force: true });
  execution.cleanup.verificationLogRemoved = true;
  await writeFile(join(directory, 'execution.json'), JSON.stringify(execution, null, 2) + '\n');
  console.log(JSON.stringify({ result: execution.result, stage, usage: execution.usage, cleanup: execution.cleanup,
    evidence: `backend/target/provider-simplified/${stage}` }));
}
