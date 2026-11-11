import { randomBytes } from 'node:crypto';
import { mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { resolve, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';
import { capture, findExecutable, assertPortFree } from './dev.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const build = join(root, 'backend', 'target', 'mq-acceptance');
const run = randomBytes(8).toString('hex');
const runtime = join(build, 'runtime', run);
const docker = findExecutable('docker');
const owned = [];
const password = randomBytes(24).toString('hex');
const env = { ...process.env, RUN_MIDDLEWARE_TESTS: 'true', RUN_BROKER_FAULT_TESTS: 'true',
  MQ_ACCEPTANCE_RUN: run, RABBITMQ_HOST: '127.0.0.1', RABBITMQ_USERNAME: 'it_user',
  RABBITMQ_PASSWORD: password, RABBITMQ_VHOST: '/zhizhida-test',
  RABBITMQ_DEFAULT_USER: 'it_user', RABBITMQ_DEFAULT_PASS: password, RABBITMQ_DEFAULT_VHOST: '/zhizhida-test',
  MYSQL_ROOT_PASSWORD: password, MYSQL_DATABASE: 'zhizhida_test', MYSQL_USER: 'it_user', MYSQL_PASSWORD: password,
  IT_DATABASE_USERNAME: 'it_user', IT_DATABASE_PASSWORD: password,
  IT_DATABASE_URL: 'jdbc:mysql://127.0.0.1:13317/zhizhida_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000',
  REDIS_RATE_HOST: '127.0.0.1', REDIS_RATE_PASSWORD: '', REDIS_CACHE_HOST: '127.0.0.1', REDIS_CACHE_PASSWORD: '',
  AI_PROVIDER: 'mock', AI_API_KEY: '', DEEPSEEK_API_KEY: '', TEMP: build, TMP: build };
for (const key of Object.keys(env)) if (/^SPRING_(DATASOURCE|FLYWAY|CONFIG|PROFILES)_/.test(key)) delete env[key];
const report = { run, startedAt: new Date().toISOString(), databasePort: 13317,
  modelProvider: 'mock', applicationRecoverySchedulerEnabled: false,
  brokerConfiguration: 'RabbitMQ 4.1 defaults; quorum at-least-once retry queue',
  noRouteRecoveryWaitSeconds: 210, tests: [], cleanup: [] };
const reportPath = join(root, 'docs', 'evidence', `rabbitmq-retry-live-${run}.json`);

async function command(args, timeout = 30000) {
  const result = await capture(docker, args, { env, timeout });
  if (result.code !== 0) throw new Error(`Docker ${args[0]} failed: ${result.output.slice(-1200)}`);
  return result.output.trim();
}
async function create(kind, image, args) {
  const name = `zhizhida-mq-acceptance-${kind}-${run}`;
  owned.push(name);
  await command(['run', '--detach', '--pull=never', '--name', name, '--label', `zhizhida.mq.acceptance=${run}`, ...args, image,
    ...(kind.startsWith('redis') ? ['redis-server', '--save', '', '--appendonly', 'no'] : [])]);
  return name;
}
async function port(name, internal) {
  const value = await command(['port', name, `${internal}/tcp`]);
  const match = /^127\.0\.0\.1:(\d+)$/.exec(value);
  if (!match) throw new Error('Test port must be loopback-only');
  return match[1];
}
async function availablePort() {
  const server = createServer();
  await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
  const result = server.address().port;
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
  return result;
}
async function ready(name, args, user) {
  const until = Date.now() + 120000;
  while (Date.now() < until) {
    const result = await capture(docker, ['exec', ...(user ? ['--user', user] : []), name, ...args], { env, timeout: 10000 });
    if (result.code === 0) return;
    await new Promise(done => setTimeout(done, 500));
  }
  throw new Error(`${name} did not become ready`);
}
try {
  await mkdir(runtime, { recursive: true });
  await mkdir(join(root, 'docs', 'evidence'), { recursive: true });
  await assertPortFree(13317);
  report.images = await command(['image', 'inspect', '--format', '{{.RepoTags}} {{.Id}}', 'mysql:8.4', 'redis:7.4.2', 'rabbitmq:4.1.0-management']);
  for (const kind of ['mysql', 'rabbit']) await mkdir(join(runtime, kind));
  env.MYSQL_TEST_CONTAINER = await create('mysql', 'mysql:8.4', ['--publish', '127.0.0.1:13317:3306',
    '--mount', `type=bind,source=${join(runtime, 'mysql')},target=/var/lib/mysql`,
    ...['MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD'].flatMap(key => ['--env', key])]);
  // Docker 为省略 HostPort 的映射在 restart 后重新分配端口；显式端口才能测试原连接恢复。
  const brokerPort = await availablePort();
  env.MQ_TEST_CONTAINER = await create('rabbit', 'rabbitmq:4.1.0-management', ['--publish', `127.0.0.1:${brokerPort}:5672`,
    '--mount', `type=bind,source=${join(runtime, 'rabbit')},target=/var/lib/rabbitmq`,
    ...['RABBITMQ_DEFAULT_USER', 'RABBITMQ_DEFAULT_PASS', 'RABBITMQ_DEFAULT_VHOST'].flatMap(key => ['--env', key])]);
  const redis = await create('redis', 'redis:7.4.2', ['--publish', '127.0.0.1::6379']);
  const cache = await create('redis-cache', 'redis:7.4.2', ['--publish', '127.0.0.1::6379']);
  console.log('[mq-acceptance] Waiting for owned MySQL, Redis and RabbitMQ containers.');
  await ready(env.MYSQL_TEST_CONTAINER, ['mysqladmin', 'ping', '--silent']);
  await ready(env.MQ_TEST_CONTAINER, ['rabbitmq-diagnostics', '-q', 'check_port_connectivity'], 'rabbitmq');
  await ready(redis, ['redis-cli', 'ping']);
  await ready(cache, ['redis-cli', 'ping']);
  env.RABBITMQ_PORT = await port(env.MQ_TEST_CONTAINER, 5672);
  env.REDIS_RATE_PORT = await port(redis, 6379);
  env.REDIS_PORT = env.REDIS_RATE_PORT;
  env.REDIS_CACHE_PORT = await port(cache, 6379);
  const selected = ['databaseOutageMovesOriginalToRetryBeforeAckAndCommitsOnceAfterRecovery',
    'globalCapacityRejectionUsesDelayedBrokerRetryWithoutSpendingModelBudget',
    'quorumRetrySurvivesBrokerRestartAndUnavailableDeadLetterRoute',
    'abandonedPublishLeaseAndLostAckDoNotDuplicateBusinessCommit',
    'actualListenerExecutesAnApiAcceptedRun', 'queuedCancellationAndExpiredBudgetRemainTerminalAfterDelivery'];
  const args = ['-o', '-B', '-Dzhizhida.build.directory=target/mq-acceptance', '-Djacoco.skip=true', '-DskipTests',
    `-Dit.test=MiddlewareApplicationIT#${selected.join('+')}`, 'test-compile'];
  const maven = findExecutable('mvn');
  let result = await capture(maven, args, { cwd: join(root, 'backend'), env, timeout: 300000 });
  await writeFile(join(build, 'compile.log'), result.output);
  if (result.code !== 0) throw new Error(`Compilation failed; see ${join(build, 'compile.log')}`);
  const heartbeat = setInterval(() => console.log('[mq-acceptance] Running six live API/SQL/AMQP failure scenarios.'), 30000);
  try {
    result = await capture(maven, args.filter(arg => !['-DskipTests', 'test-compile'].includes(arg))
      .concat('failsafe:integration-test', 'failsafe:verify'), { cwd: join(root, 'backend'), env, timeout: 300000 });
  } finally { clearInterval(heartbeat); }
  await writeFile(join(build, 'acceptance.log'), result.output);
  const reports = join(build, 'failsafe-reports');
  for (const file of await readdir(reports)) {
    if (!file.startsWith('TEST-') || !file.endsWith('.xml')) continue;
    const xml = await readFile(join(reports, file), 'utf8');
    report.tests.push(...Array.from(xml.matchAll(/<testcase\s+name="([^"]+)"[^>]*time="([^"]+)"([^>]*)(?:\/>|>([\s\S]*?)<\/testcase>)/g), match => ({
      name: match[1], seconds: Number(match[2]), passed: !/<(?:failure|error|skipped)\b/.test(match[4] || '') })));
  }
  report.passed = result.code === 0 && report.tests.length === selected.length && report.tests.every(test => test.passed);
  if (!report.passed) throw new Error(`Live MQ acceptance failed; see ${join(build, 'acceptance.log')}`);
  console.log('[mq-acceptance] All six live fault scenarios passed.');
} catch (error) {
  report.passed = false;
  report.error = error.message;
  process.exitCode = 1;
  console.error(error.message);
} finally {
  if (env.MQ_TEST_CONTAINER) {
    const logs = await capture(docker, ['logs', '--tail', '400', env.MQ_TEST_CONTAINER], { env, timeout: 10000 });
    await writeFile(join(build, 'broker.log'), logs.output);
  }
  for (const name of owned.reverse()) {
    try {
      const label = await command(['inspect', '--format', '{{index .Config.Labels "zhizhida.mq.acceptance"}}', name]);
      if (label !== run) throw new Error(`Refusing cleanup of unowned container ${name}`);
      await command(['rm', '--force', '--volumes', name]);
      report.cleanup.push({ container: name, removed: true });
    } catch (error) { report.cleanup.push({ container: name, error: error.message }); process.exitCode = 1; }
  }
  if (report.cleanup.every(item => item.removed)) {
    const actual = await realpath(runtime), allowed = await realpath(join(build, 'runtime'));
    if (!actual.startsWith(allowed + sep) || !actual.startsWith(root + sep)) throw new Error('Cleanup escaped project');
    await rm(actual, { recursive: true });
  }
  report.finishedAt = new Date().toISOString();
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  console.log(`[mq-acceptance] Report: ${reportPath}`);
}
