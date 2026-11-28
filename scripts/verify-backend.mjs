import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, realpath, rm, open, access } from 'node:fs/promises';
import { dirname, join, resolve, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { finished } from 'node:stream/promises';
import { findExecutable, assertPortFree, capture, stopChild } from './dev.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

// 验证流程不读取 .env、不复用已有数据库、不调整 Docker 守护进程，也不安装全局工具。
export async function verifyBackend(args = process.argv.slice(2), mavenArgs = [], stages) {
  if (args.some((arg) => !['--offline', '--integration-only', '--help'].includes(arg))) throw new Error('Usage: node scripts/verify-backend.mjs [--offline] [--integration-only]');
  if (args.includes('--help')) {
    console.log('Create an isolated MySQL using mysqld/mysql from PATH, run Maven verify, then remove only this run\'s database.');
    console.log('--integration-only: run all integration tests and merge/check coverage after a completed mvn test.');
    return;
  }
  const port = Number(process.env.IT_MYSQL_PORT || 13307);
  const overrides = mavenArgs.filter(arg => arg.startsWith('-Dzhizhida.build.directory='));
  if (overrides.length > 1) throw new Error('Only one build directory may participate in verification');
  const buildDirectory = resolve(root, 'backend', overrides[0]?.slice('-Dzhizhida.build.directory='.length) || 'target');
  const buildRelative = relative(root, buildDirectory);
  if (!buildRelative || buildRelative.startsWith('..') || isAbsolute(buildRelative)) throw new Error('Build directory must stay inside the project');
  // 分开运行单测和集成测试时，两次执行须使用同一构建目录才能合并覆盖率数据。
  const goals = args.includes('--integration-only')
    ? ['test-compile', 'jacoco:prepare-agent-integration@prepare-agent-integration', 'failsafe:integration-test', 'failsafe:verify',
      'jacoco:merge@merge-coverage', 'jacoco:report@report', 'jacoco:check@coverage-gate']
    : ['verify'];
  if (args.includes('--integration-only')) {
    await access(join(buildDirectory, 'jacoco.exec'))
      .catch(() => { throw new Error('Run mvn test in backend before --integration-only to collect unit coverage.'); });
  }
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('IT_MYSQL_PORT must be 1024..65535');
  await assertPortFree(port);
  const mysqld = findExecutable('mysqld');
  const mysql = findExecutable('mysql');
  const mysqladmin = findExecutable('mysqladmin');
  const maven = findExecutable('mvn');
  const parent = join(root, '.runtime', 'integration');
  await mkdir(parent, { recursive: true });
  const runDir = await mkdtemp(join(parent, 'mysql-'));
  const data = join(runDir, 'data');
  const temporary = join(runDir, 'tmp');
  await mkdir(temporary);
  const password = randomBytes(24).toString('hex');
  const common = ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', `--port=${port}`, '--user=root'];
  const env = { ...process.env, MAVEN_OPTS: `${process.env.MAVEN_OPTS || ''} -Dspring.test.context.cache.maxSize=6`, TMP: temporary, TEMP: temporary, MYSQL_TEST_LOGIN_FILE: join(temporary, 'no-login-file'),
    IT_DATABASE_URL: `jdbc:mysql://127.0.0.1:${port}/zhizhida_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000`,
    IT_DATABASE_USERNAME: 'it_user', IT_DATABASE_PASSWORD: password,
    AI_PROVIDER: process.env.RUN_REAL_AGENT_EVAL === 'true' ? 'deepseek' : 'mock',
    AI_API_KEY: process.env.RUN_REAL_AGENT_EVAL === 'true' ? process.env.AI_API_KEY || '' : '',
    DEEPSEEK_API_KEY: process.env.RUN_REAL_AGENT_EVAL === 'true' ? process.env.DEEPSEEK_API_KEY || '' : '' };
  // 清除开发环境的数据源覆盖项，确保本次验证始终使用独立测试配置。
  for (const key of Object.keys(env)) {
    if (/^SPRING_(DATASOURCE|FLYWAY|CONFIG|PROFILES)_/.test(key)) delete env[key];
  }
  let server;
  let serverLog;
  const controller = new AbortController();
  const interrupt = () => controller.abort();
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', interrupt);
  try {
    console.log('[integration] Initializing a disposable MySQL; existing servers remain untouched.');
    const init = await capture(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`,
      `--datadir=${data}`, '--initialize-insecure', `--log-error=${join(runDir, 'initialize.log')}`],
    { cwd: runDir, env, timeout: 120000, signal: controller.signal });
    if (init.code !== 0) throw new Error(`MySQL initialization failed (${init.code}); ${init.output.slice(-1200)}`);
    serverLog = await open(join(runDir, 'mysql-console.log'), 'w');
    server = spawn(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`, `--datadir=${data}`,
      `--tmpdir=${temporary}`, `--port=${port}`, '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin',
      `--log-error=${join(runDir, 'mysql.log')}`, `--pid-file=${join(runDir, 'mysql.pid')}`],
    { cwd: runDir, env, windowsHide: true, detached: process.platform !== 'win32',
      stdio: ['ignore', serverLog.fd, serverLog.fd] });
    let serverError;
    server.on('error', (error) => { serverError = error; });
    const deadline = Date.now() + 60000;
    let ready = false;
    while (Date.now() < deadline && !controller.signal.aborted) {
      if (serverError || server.exitCode !== null) throw new Error('Disposable MySQL exited before readiness');
      const ping = await capture(mysqladmin, [...common, 'ping'], { cwd: runDir, env, timeout: 5000, signal: controller.signal });
      if (ping.code === 0) { ready = true; break; }
      await sleep(500);
    }
    if (!ready) throw new Error('Disposable MySQL did not become ready in 60 seconds');
    // 密码仅通过标准输入和子进程环境传递，避免出现在命令参数或日志中。
    await new Promise((done, reject) => {
      const client = spawn(mysql, common, { cwd: runDir, env, windowsHide: true,
        detached: process.platform !== 'win32', stdio: ['pipe', 'ignore', 'pipe'] });
      client.stderr.resume();
      const timeout = setTimeout(() => { void stopChild(client); reject(new Error('MySQL bootstrap timed out')); }, 15000);
      client.on('error', (error) => { clearTimeout(timeout); reject(error); });
      client.on('close', (code) => { clearTimeout(timeout); code === 0 ? done() : reject(new Error(`MySQL bootstrap failed (${code})`)); });
      client.stdin.on('error', reject);
      client.stdin.end(`ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}';\n`
        + `CREATE DATABASE zhizhida_test CHARACTER SET utf8mb4;\n`
        + `CREATE USER 'it_user'@'127.0.0.1' IDENTIFIED BY '${password}';\n`
        + `CREATE USER 'it_user'@'localhost' IDENTIFIED BY '${password}';\n`
        + `GRANT ALL ON zhizhida_test.* TO 'it_user'@'127.0.0.1';\n`
        + `GRANT ALL ON zhizhida_test.* TO 'it_user'@'localhost';\n`);
    });
    if (controller.signal.aborted) throw new Error('Verification interrupted');
    const plan = stages || [goals];
    console.log(`[integration] MySQL ready on 127.0.0.1:${port}; running ${plan.length} Maven stage(s).`);
    // capture 兼容 Windows 的 .cmd 路径，超时仅终止自身启动的子进程树。
    await mkdir(buildDirectory, { recursive: true });
    const report = createWriteStream(join(buildDirectory, 'verification.log'));
    report.on('error', interrupt);
    const heartbeat = setInterval(() => console.log('[integration] Maven verify is running; reports: ' + buildDirectory), 30000);
    try {
      for (const stage of plan) {
        const result = await capture(maven, [...(args.includes('--offline') ? ['-o'] : []), '-B', ...mavenArgs, ...stage],
          { cwd: join(root, 'backend'), env, timeout: 300000, signal: controller.signal, onOutput: (data) => report.write(data) });
        console.log(result.output);
        if (result.code !== 0) throw new Error(`Maven verification failed (${result.code}); inspect Surefire/Failsafe reports.`);
      }
    } finally { clearInterval(heartbeat); report.end(); await finished(report); }
  } finally {
    if (server?.pid && server.exitCode === null && server.signalCode === null) {
      await capture(mysqladmin, [...common, 'shutdown'], { cwd: runDir,
        env: { ...env, MYSQL_PWD: password }, timeout: 15000 }).catch(() => {});
      await stopChild(server);
    }
    await serverLog?.close();
    // runDir 由本次调用独占创建；清理范围仅限该目录，不递归删除已有数据库。
    const actual = await realpath(runDir);
    const allowed = await realpath(parent);
    if (!actual.startsWith(allowed + sep) || !actual.startsWith(resolve(runDir))) {
      throw new Error('Refusing cleanup outside the owned integration directory');
    }
    await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    console.log('[integration] Removed this run\'s MySQL data and stopped its process.');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verifyBackend().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
