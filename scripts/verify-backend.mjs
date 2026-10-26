import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, realpath, rm, open } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { finished } from 'node:stream/promises';
import { findExecutable, assertPortFree, capture, stopChild } from './dev.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

// No .env reads, existing database reuse, Docker daemon changes, or global tool installation.
export async function verifyBackend(args = process.argv.slice(2)) {
  if (args.some((arg) => !['--offline', '--help'].includes(arg))) throw new Error('Usage: node scripts/verify-backend.mjs [--offline]');
  if (args.includes('--help')) {
    console.log('Create an isolated MySQL using mysqld/mysql from PATH, run Maven verify, then remove only this run\'s database.');
    return;
  }
  const port = Number(process.env.IT_MYSQL_PORT || 13307);
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
  const env = { ...process.env, TMP: temporary, TEMP: temporary, MYSQL_TEST_LOGIN_FILE: join(temporary, 'no-login-file'),
    IT_DATABASE_URL: `jdbc:mysql://127.0.0.1:${port}/zhizhida_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000`,
    IT_DATABASE_USERNAME: 'it_user', IT_DATABASE_PASSWORD: password,
    AI_PROVIDER: 'mock', AI_API_KEY: '', DEEPSEEK_API_KEY: '' };
  // A developer datasource must never override this run's isolated test config.
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
    // Password goes through stdin and the child environment, never command arguments or logs.
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
    console.log(`[integration] MySQL ready on 127.0.0.1:${port}; running unit + integration + coverage gates.`);
    // capture handles Windows .cmd paths and kills only its own child process tree on timeout.
    await mkdir(join(root, 'backend', 'target'), { recursive: true });
    const report = createWriteStream(join(root, 'backend', 'target', 'verification.log'));
    report.on('error', interrupt);
    const heartbeat = setInterval(() => console.log('[integration] Maven verify is running; reports: backend/target/'), 30000);
    let result;
    try {
      result = await capture(maven, [...(args.includes('--offline') ? ['-o'] : []), '-B', 'verify'],
        { cwd: join(root, 'backend'), env, timeout: 300000, signal: controller.signal, onOutput: (data) => report.write(data) });
    } finally { clearInterval(heartbeat); report.end(); await finished(report); }
    console.log(result.output);
    if (result.code !== 0) throw new Error(`Maven verify failed (${result.code}); inspect Surefire/Failsafe reports.`);
  } finally {
    if (server?.pid && server.exitCode === null && server.signalCode === null) {
      await capture(mysqladmin, [...common, 'shutdown'], { cwd: runDir,
        env: { ...env, MYSQL_PWD: password }, timeout: 15000 }).catch(() => {});
      await stopChild(server);
    }
    await serverLog?.close();
    // runDir was created exclusively by this invocation. Never recursively remove an existing DB.
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
