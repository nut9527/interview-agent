import { randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { finished } from 'node:stream/promises';
import { mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { resolve, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { testCluster } from './rocketmq-test-cluster.mjs';
import { capture, findExecutable, assertPortFree } from './dev.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const run = randomBytes(8).toString('hex');
const build = join(root, 'backend', 'target', 'mq-acceptance', run);
const runtime = join(build, 'runtime', run);
const docker = findExecutable('docker');
const owned = [];
const cluster = testCluster(run);
const password = randomBytes(24).toString('hex');
const env = { ...process.env, RUN_MIDDLEWARE_TESTS: 'true', RUN_BROKER_FAULT_TESTS: 'true',
  MQ_ACCEPTANCE_RUN: run,
  MYSQL_ROOT_PASSWORD: password, MYSQL_DATABASE: 'zhizhida_test', MYSQL_USER: 'it_user', MYSQL_PASSWORD: password,
  IT_DATABASE_USERNAME: 'it_user', IT_DATABASE_PASSWORD: password,
  IT_DATABASE_URL: 'jdbc:mysql://127.0.0.1:13317/zhizhida_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000',
  REDIS_RATE_HOST: '127.0.0.1', REDIS_RATE_PASSWORD: '', REDIS_CACHE_HOST: '127.0.0.1', REDIS_CACHE_PASSWORD: '',
  AI_PROVIDER: 'mock', AI_API_KEY: '', DEEPSEEK_API_KEY: '', TEMP: build, TMP: build };
for (const key of Object.keys(env)) if (/^SPRING_(DATASOURCE|FLYWAY|CONFIG|PROFILES)_/.test(key)) delete env[key];
const report = { run, databasePort: 13317,
  modelProvider: 'mock', applicationRecoverySchedulerEnabled: false,
  brokerConfiguration: 'RocketMQ 5.3.2, synchronous disk flush, native delay topics',
  sdkVersion: '5.2.2', tests: [], cleanup: [] };
const reportPath = join(build, 'report.json');
let fullSuite = false;

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
async function ready(name, args) {
  const until = Date.now() + 120000;
  while (Date.now() < until) {
    const result = await capture(docker, ['exec', name, ...args], { env, timeout: 10000 });
    if (result.code === 0) return;
    await new Promise(done => setTimeout(done, 500));
  }
  throw new Error(`${name} did not become ready`);
}
try {
  await mkdir(runtime, { recursive: true });
  await mkdir(join(root, 'docs', 'evidence'), { recursive: true });
  await assertPortFree(13317);
  report.images = await command(['image', 'inspect', '--format', '{{.RepoTags}} {{.Id}}', 'mysql:8.4', 'redis:7.4.2', 'apache/rocketmq:5.3.2']);
  await mkdir(join(runtime, 'mysql'));
  env.MYSQL_TEST_CONTAINER = await create('mysql', 'mysql:8.4', ['--publish', '127.0.0.1:13317:3306',
    '--mount', `type=bind,source=${join(runtime, 'mysql')},target=/var/lib/mysql`,
    ...['MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD'].flatMap(key => ['--env', key])]);
  Object.assign(env, await cluster.start());
  if (!process.argv.slice(2).length) report.containerClient = await cluster.verifyContainerClient();
  const brokerSettings = await command(['exec', env.MQ_TEST_CONTAINER, 'cat', '/dispatch/broker.conf']);
  report.brokerSettings = Object.fromEntries(brokerSettings.split(/\r?\n/).filter(line =>
    /^(sendMessageThreadPoolNums|pullMessageThreadPoolNums|ackMessageThreadPoolNums|flushDiskType|timerWheelEnable)=/.test(line))
    .map(line => line.split('=')));
  report.brokerSettings.jvm = await command(['exec', env.MQ_TEST_CONTAINER, 'printenv', 'JAVA_OPT_EXT']);
  const redis = await create('redis', 'redis:7.4.2', ['--publish', '127.0.0.1::6379']);
  const cache = await create('redis-cache', 'redis:7.4.2', ['--publish', '127.0.0.1::6379']);
  console.log('[mq-acceptance] Waiting for owned MySQL, Redis and RocketMQ containers.');
  await ready(env.MYSQL_TEST_CONTAINER, ['mysqladmin', 'ping', '--silent']);
  await ready(redis, ['redis-cli', 'ping']);
  await ready(cache, ['redis-cli', 'ping']);
  env.REDIS_RATE_PORT = await port(redis, 6379);
  env.REDIS_PORT = env.REDIS_RATE_PORT;
  env.REDIS_CACHE_PORT = await port(cache, 6379);
  const revision = await capture(findExecutable('git'), ['rev-parse', 'HEAD'], { cwd: root, env, timeout: 5000 });
  report.revision = revision.code === 0 ? revision.output.trim() : null;
  const status = await capture(findExecutable('git'), ['status', '--porcelain', '--untracked-files=normal'], { cwd: root, env, timeout: 10000 });
  report.workingTreeModified = status.code === 0 ? Boolean(status.output.trim()) : null;
  const cases = ['databaseOutageMovesOriginalToRetryBeforeAckAndCommitsOnceAfterRecovery',
    'globalCapacityRejectionUsesDelayedBrokerRetryWithoutSpendingModelBudget',
    'confirmedDelaySurvivesBrokerRestart',
    'abandonedPublishLeaseAndLostAckDoNotDuplicateBusinessCommit',
    'proxyRestartResumesBufferedWork', 'actualListenerExecutesAnApiAcceptedRun', 'queuedCancellationAndExpiredBudgetRemainTerminalAfterDelivery'];
  const diagnostics = ['invalidMessageIsQuarantinedAndQueryableByKey', 'exhaustedRedeliveryGoesToConsumerGroupDeadLetterTopic'];
  const methods = [...cases, ...diagnostics];
  const requested = process.argv.slice(2);
  if (requested.some(name => !methods.includes(name))) throw new Error('Unknown fault scenario');
  const selected = requested.length ? [...new Set(requested)] : methods;
  fullSuite = selected.length === methods.length;
  // 重启 Broker 或 Proxy 的用例单独运行，避免断连后的客户端状态影响其他组。
  const groups = [
    ['MiddlewareApplicationIT', cases.filter(name => !['confirmedDelaySurvivesBrokerRestart', 'proxyRestartResumesBufferedWork'].includes(name))],
    ['MiddlewareApplicationIT', ['confirmedDelaySurvivesBrokerRestart', 'proxyRestartResumesBufferedWork']],
    ['RocketDiagnosticsIT', diagnostics],
  ];
  const args = ['-o', '-B', `-Dzhizhida.build.directory=target/mq-acceptance/${run}`, '-Djacoco.skip=true'];
  const maven = findExecutable('mvn');
  const compiled = await capture(maven, [...args, '-DskipTests', 'test-compile'], { cwd: join(root, 'backend'), env, timeout: 300000 });
  await writeFile(join(build, 'compile.log'), compiled.output);
  if (compiled.code !== 0) throw new Error(`Compilation failed; see ${join(build, 'compile.log')}`);
  let passed = true;
  const reports = join(build, 'failsafe-reports');
  const log = createWriteStream(join(build, 'acceptance.log'));
  try {
    for (const [suite, methods] of groups) {
      const names = methods.filter(name => selected.includes(name));
      if (!names.length) continue;
      console.log(`[mq-acceptance] Running ${names.join(', ')}`);
      const selector = '-Dit.test=' + suite + '#' + names.join('+');
      const runGroup = () => capture(maven, [...args, selector, 'failsafe:integration-test', 'failsafe:verify'],
        { cwd: join(root, 'backend'), env, timeout: 300000, onOutput: chunk => log.write(chunk) });
      let result;
      try {
        result = await runGroup();
      } catch (error) {
        log.write(error.message + '\n');
        if (!/超时|timed out/i.test(error.message)) throw error;
        console.log('[mq-acceptance] Timed out; retrying this group once.');
        try { result = await runGroup(); }
        catch (retryError) {
          log.write(retryError.message + '\n');
          passed = false;
          continue;
        }
      }
      passed = passed && result.code === 0;
      // 各组共用 Failsafe 报告目录，只计入本组选中的用例，并将跳过项判为未通过。
      for (const file of await readdir(reports)) {
        if (!file.startsWith('TEST-') || !file.endsWith(suite + '.xml')) continue;
        const xml = await readFile(join(reports, file), 'utf8');
        for (const match of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
          const name = /\bname="([^"]+)"/.exec(match[1])?.[1];
          if (names.includes(name)) report.tests.push({ name, seconds: Number(/\btime="([^"]+)"/.exec(match[1])?.[1]),
            passed: !/<(?:failure|error|skipped)\b/.test(match[2] || '') });
        }
      }
    }
  } finally { log.end(); await finished(log); }
  report.passed = passed && report.tests.length === selected.length && selected.every(name => report.tests.some(test => test.name === name && test.passed));
  if (!report.passed) throw new Error(`Live MQ acceptance failed; see ${join(build, 'acceptance.log')}`);
  console.log('[mq-acceptance] All requested live fault scenarios passed.');
} catch (error) {
  report.passed = false;
  report.error = error.message;
  process.exitCode = 1;
  console.error(error.message);
} finally {
  if (env.MQ_TEST_CONTAINER) {
    try {
      const logs = await capture(docker, ['logs', '--tail', '400', env.MQ_TEST_CONTAINER], { env, timeout: 10000 });
      await writeFile(join(build, 'broker.log'), logs.output);
    } catch (error) { console.error('Broker log unavailable: ' + error.message); }
  }
  try {
    await cluster.close();
    report.cleanup.push({ cluster: 'zdmq' + run, removed: true });
  } catch (error) { report.cleanup.push({ cluster: run, error: error.message }); process.exitCode = 1; }
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
  const serialized = JSON.stringify(report, null, 2) + '\n';
  await writeFile(reportPath, serialized);
  // 专项复跑保留独立报告，只有全套验收通过且清理成功才更新仓库中的验收记录。
  if (fullSuite && report.passed && !process.exitCode) await writeFile(join(root, 'docs', 'evidence', 'rocketmq-acceptance.json'), serialized);
  console.log(`[mq-acceptance] Report: ${reportPath}`);
}
