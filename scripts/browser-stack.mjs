// 浏览器验收专用环境：空库、独立协调 Redis、Mock 和生产前端，不读取开发配置。
import { spawn } from 'node:child_process';
import { createConnection } from 'node:net';
import { access, mkdir, mkdtemp, open, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomBytes } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';
import { PROJECT_ROOT, acceptsBackend, assertPortFree, capture, findExecutable, stopChild, waitForHttp } from './dev.mjs';

const backendOrigin = 'http://127.0.0.1:18080';
const origin = 'http://127.0.0.1:15173';
const systemKeys = /^(?:PATH|PATHEXT|SYSTEMROOT|SYSTEMDRIVE|WINDIR|COMSPEC|HOME|USERPROFILE|HOMEDRIVE|HOMEPATH|APPDATA|LOCALAPPDATA|LANG|LC_ALL|TZ|CI|DISPLAY|WAYLAND_DISPLAY|XDG_RUNTIME_DIR|PLAYWRIGHT_BROWSERS_PATH)$/i;

export function wslPathArguments(distro, windowsPath) {
  return ['-d', distro, '--exec', 'wslpath', '-a', windowsPath.replaceAll('\\', '/')];
}

export function ownedWslDirectory(output, tmp) {
  // 合并 WSL 的 UTF-16 提示和 wslpath 的 UTF-8 输出时，路径行前可能残留空字符。
  const paths = output.replaceAll('\0', '').split(/\r?\n/).map(line => line.trim()).filter(line => line.startsWith('/'));
  const suffix = '/' + relative(PROJECT_ROOT, tmp).replaceAll('\\', '/');
  if (paths.length !== 1 || !paths[0].endsWith(suffix)) throw new Error('Cannot resolve the owned Redis directory inside WSL.');
  return paths[0];
}

export function browserEnvironment(base, { dir, password, ciServices = false }) {
  if (ciServices && base.CI !== 'true') throw new Error('--ci-services requires CI=true and disposable CI services.');
  const tmp = join(dir, 'tmp');
  return {
    ...Object.fromEntries(Object.entries(base).filter(([key]) => systemKeys.test(key))),
    TMP: tmp, TEMP: tmp, TMPDIR: tmp,
    AI_PROVIDER: 'mock', AI_API_KEY: '', DEEPSEEK_API_KEY: '', DEEPSEEK_BASE_URL: 'http://127.0.0.1:1',
    SPRING_PROFILES_ACTIVE: 'test',
    SPRING_CONFIG_IMPORT: pathToFileURL(join(dir, 'empty.properties')).href,
    SPRING_DOCKER_COMPOSE_ENABLED: 'false', SERVER_PORT: '18080', SERVER_ADDRESS: '127.0.0.1',
    SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:13308/browser_e2e?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000',
    SPRING_DATASOURCE_USERNAME: ciServices ? 'it_user' : 'root',
    SPRING_DATASOURCE_PASSWORD: ciServices ? 'ci_ephemeral_only' : password,
    MYSQL_PWD: password, MYSQL_TEST_LOGIN_FILE: join(tmp, 'no-login'),
    REDIS_HOST: '127.0.0.1', REDIS_PORT: '16379', REDIS_PASSWORD: '',
    REDIS_RATE_HOST: '127.0.0.1', REDIS_RATE_PORT: '16379', REDIS_RATE_PASSWORD: '',
    REDIS_NAMESPACE: 'zhizhida:browser:' + randomBytes(12).toString('hex'),
    REDIS_RATE_LIMIT_ENABLED: 'false', QUESTION_CACHE_ENABLED: 'false', REDIS_HEALTH_ENABLED: 'true',
    RUN_DISPATCH_MODE: 'database', KNOWLEDGE_DISPATCH_MODE: 'local',
    AGENT_VECTOR_ENABLED: 'false', AGENT_VECTOR_WORKER_ENABLED: 'false', AGENT_MCP_REFERENCE_ENABLED: 'false',
    AGENT_TRACE_EXPORT_ENABLED: 'false', LEARNING_PRACTICE_TOOLS_ENABLED: 'false',
    INTERVIEW_RUNS_WORKER_ENABLED: 'true', INTERVIEW_RUNS_LOOP_POLICY: 'B0',
    INTERVIEW_RUNS_DECISION_PROTOCOL: 'phased-v2', INTERVIEW_ADAPTIVE_ROUTING_ENABLED: 'false',
    INTERVIEW_RUNS_SCAN_MS: '100', INTERVIEW_SSE_POLL_MS: '100', DEMO_ENABLED: 'true',
    KNOWLEDGE_IMPORT_SHARED_DIRECTORY: join(tmp, 'knowledge-shared'),
    RESUME_PDFBOX_FONT_CACHE_DIR: join(tmp, 'pdfbox-font-cache'),
    QUOTA_AI_PER_MINUTE: '100', QUOTA_AI_UNITS_PER_DAY: '1000',
    VITE_API_BASE_URL: '', VITE_API_PROXY_TARGET: backendOrigin,
  };
}

// 只用于本次独立 Redis 的只读探活，以及本次拥有进程的无持久化退出。
function redisRequest(args, expected, allowEof = false) {
  return new Promise((done, reject) => {
    const socket = createConnection({ host: '127.0.0.1', port: 16379 });
    let text = '', settled = false;
    const finish = error => {
      if (settled) return;
      settled = true; socket.destroy(); error ? reject(error) : done(text);
    };
    socket.setTimeout(2000, () => finish(new Error('Browser Redis command timed out')));
    socket.on('error', finish);
    socket.on('connect', () => socket.write(`*${args.length}\r\n` + args.map(arg => `$${Buffer.byteLength(arg)}\r\n${arg}\r\n`).join('')));
    socket.on('data', data => {
      text += data;
      if (text.startsWith('-')) finish(new Error('Browser Redis rejected readiness command'));
      else if (expected && expected.test(text)) finish();
    });
    socket.on('end', () => finish(allowEof ? undefined : new Error('Browser Redis disconnected')));
  });
}

export async function startBrowserStack({ ciServices = false, signal, offline = false } = {}) {
  // 浏览器验收始终使用本机依赖，保留 offline 参数以兼容统一启动入口。
  void offline;
  signal?.throwIfAborted();
  if (ciServices && process.env.CI !== 'true') throw new Error('--ci-services requires CI=true and disposable CI services.');
  const jar = resolve(PROJECT_ROOT, process.env.BROWSER_BACKEND_JAR || 'backend/target/zhizhida-backend-0.1.0-SNAPSHOT.jar');
  const jarRelative = relative(PROJECT_ROOT, await realpath(jar));
  if (jarRelative.startsWith('..') || isAbsolute(jarRelative)) throw new Error('BROWSER_BACKEND_JAR must stay inside this project.');
  await access(join(PROJECT_ROOT, 'frontend/dist/index.html'));
  await assertPortFree(18080); await assertPortFree(15173);
  if (!ciServices) { await assertPortFree(13308); await assertPortFree(16379); }
  const parent = join(PROJECT_ROOT, '.runtime'); await mkdir(parent, { recursive: true });
  if (!(await realpath(parent)).startsWith(await realpath(PROJECT_ROOT) + sep)) throw new Error('Unsafe browser runtime directory');
  const dir = await mkdtemp(join(parent, 'browser-stack-')), tmp = join(dir, 'tmp');
  await mkdir(tmp); await writeFile(join(dir, 'empty.properties'), '');
  const password = randomBytes(24).toString('hex');
  const environment = browserEnvironment(process.env, { dir, password, ciServices });
  const children = [], logs = [], failures = new Map();
  const common = ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=13308', '--user=root'];
  let mysqlServer, redisServer, cleanupPromise, interruptedStops;
  const active = child => child?.exitCode === null && child.signalCode === null && !failures.has(child);
  const check = child => { signal?.throwIfAborted(); if (child && !active(child)) throw new Error('Browser service exited before readiness; see diagnostics.'); };
  const launch = async (executable, args, filename, { input = false, env = environment } = {}) => {
    signal?.throwIfAborted();
    const log = await open(join(dir, filename), 'w'); logs.push(log);
    const child = spawn(executable, args, { cwd: dir, env, windowsHide: true, detached: process.platform !== 'win32', stdio: [input ? 'pipe' : 'ignore', log.fd, log.fd] });
    children.push(child); child.on('error', error => failures.set(child, error));
    return child;
  };
  // 取消时先停应用服务。调用方关闭 Chromium 后再清理浏览器目录，MySQL 和 Redis 由 cleanup 正常关闭。
  const interrupted = () => {
    interruptedStops = Promise.allSettled(children.filter(child => child !== mysqlServer && child !== redisServer).map(stopChild));
  };
  const cleanup = () => cleanupPromise ??= (async () => {
    signal?.removeEventListener('abort', interrupted);
    await interruptedStops;
    const errors = [];
    for (const child of [...children].reverse()) {
      try {
        if (child === redisServer && active(child)) await redisRequest(['SHUTDOWN', 'NOSAVE'], null, true).catch(() => {});
        if (child === mysqlServer && active(child)) await capture(findExecutable('mysqladmin'), [...common, 'shutdown'], { env: environment, cwd: dir, timeout: 10000 }).catch(() => {});
        await stopChild(child);
        for (let n = 0; active(child) && n < 40; n++) await sleep(50);
        if (active(child)) throw new Error('Owned browser service did not stop');
      } catch (error) { errors.push(error); }
    }
    for (const log of logs) await log.close().catch(error => errors.push(error));
    if (errors.length) throw new AggregateError(errors, `Could not clean browser stack at ${dir}`);
    const actual = await realpath(dir), allowed = await realpath(parent);
    if (actual !== resolve(dir) || !actual.startsWith(allowed + sep + 'browser-stack-')) throw new Error('Unsafe browser stack cleanup path');
    await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
  })();
  try {
    if (!ciServices) {
      const distro = process.env.BROWSER_REDIS_WSL_DISTRO;
      if (distro && process.platform !== 'win32') throw new Error('BROWSER_REDIS_WSL_DISTRO is only supported on Windows.');
      const redis = process.env.BROWSER_REDIS_EXECUTABLE || (distro ? 'redis-server' : findExecutable('redis-server'));
      const redisExe = distro ? findExecutable('wsl') : redis;
      const prefix = distro ? ['-d', distro, '--exec'] : [];
      const version = await capture(redisExe, [...prefix, ...(distro ? [redis] : []), '--version'], { env: environment, cwd: dir, timeout: 10000, signal });
      if (version.code !== 0 || Number(version.output.match(/\bv=(\d+)\./)?.[1] || 0) < 6) throw new Error('Browser tests require a native Redis 6+ or explicit BROWSER_REDIS_WSL_DISTRO.');
      let redisDir = tmp;
      if (distro) {
        const translated = await capture(redisExe, wslPathArguments(distro, tmp), { env: environment, cwd: dir, timeout: 10000, signal });
        if (translated.code !== 0) throw new Error('Cannot resolve the owned Redis directory inside WSL.');
        redisDir = ownedWslDirectory(translated.output, tmp);
      }
      const quoted = value => '"' + value.replaceAll('\\', '/').replaceAll('"', '\\"') + '"';
      await writeFile(join(tmp, 'redis.conf'), `bind 127.0.0.1\nport 16379\nprotected-mode yes\ndaemonize no\nsave ""\nappendonly no\nmaxmemory 256mb\nmaxmemory-policy noeviction\ndir ${quoted(redisDir)}\nlogfile ${quoted(redisDir + '/redis.log')}\n`);
      redisServer = await launch(redisExe, [...prefix, ...(distro ? [redis] : []), distro ? redisDir + '/redis.conf' : join(tmp, 'redis.conf')], 'redis-console.log');
      const mysqld = findExecutable('mysqld'), data = join(dir, 'mysql-data');
      const initialized = await capture(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`, `--datadir=${data}`, '--initialize-insecure', `--log-error=${join(dir, 'mysql-initialize.log')}`], { env: environment, cwd: dir, timeout: 120000, signal });
      if (initialized.code !== 0) throw new Error('Browser MySQL initialization failed.');
      mysqlServer = await launch(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`, `--datadir=${data}`, `--tmpdir=${tmp}`, '--port=13308', '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin', `--log-error=${join(dir, 'mysql.log')}`], 'mysql-console.log');
      const deadline = Date.now() + 30000;
      for (;;) {
        check(mysqlServer);
        const ping = await capture(findExecutable('mysqladmin'), [...common, 'ping'], { env: { ...environment, MYSQL_PWD: '' }, cwd: dir, timeout: 3000, signal });
        if (ping.code === 0) break;
        if (Date.now() > deadline) throw new Error('Browser MySQL readiness timed out.');
        await sleep(250, undefined, { signal });
      }
      const client = await launch(findExecutable('mysql'), common, 'mysql-bootstrap.log', { input: true, env: { ...environment, MYSQL_PWD: '' } });
      await new Promise((done, reject) => {
        const timer = setTimeout(() => { void stopChild(client).then(() => reject(new Error('Browser database bootstrap timed out')), reject); }, 15000);
        const abort = () => { void stopChild(client).then(() => reject(new Error('Browser database bootstrap interrupted')), reject); };
        signal?.addEventListener('abort', abort, { once: true });
        const finish = error => { clearTimeout(timer); signal?.removeEventListener('abort', abort); error ? reject(error) : done(); };
        client.once('error', finish); client.once('close', code => finish(code === 0 ? null : new Error('Browser database bootstrap failed'))); client.stdin.on('error', finish);
        client.stdin.end(`ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}'; CREATE DATABASE browser_e2e CHARACTER SET utf8mb4;`);
      });
    }
    const deadline = Date.now() + 15000;
    for (;;) {
      check(redisServer);
      try {
        await redisRequest(['PING'], /^\+PONG\r\n$/);
        await redisRequest(['CONFIG', 'GET', 'maxmemory-policy'], /\r\nnoeviction\r\n$/); break;
      } catch (error) { if (Date.now() > deadline) throw error; await sleep(250, undefined, { signal }); }
    }
    const backend = await launch(findExecutable('java'), ['-XX:-UsePerfData', '-Djava.io.tmpdir=' + tmp, '-jar', jar], 'backend.log');
    await waitForHttp(backendOrigin + '/api/health/ready', acceptsBackend, { timeout: 60000, alive: () => !signal?.aborted && active(backend) });
    // 预览已构建的 dist，沿用 vite.config.ts 中的后端代理。
    const frontend = await launch(process.execPath, [join(PROJECT_ROOT, 'frontend/node_modules/vite/bin/vite.js'), 'preview', '--config', join(PROJECT_ROOT, 'frontend/vite.config.ts'), '--host', '127.0.0.1', '--port', '15173', '--strictPort', '--outDir', join(PROJECT_ROOT, 'frontend/dist')], 'frontend.log');
    await waitForHttp(origin + '/api/health/ready', acceptsBackend, { timeout: 30000, alive: () => !signal?.aborted && active(frontend) });
    signal?.throwIfAborted(); signal?.addEventListener('abort', interrupted, { once: true });
    return { origin, dir, tmp, environment, cleanup };
  } catch (error) {
    error.browserStackLogs = Object.fromEntries(await Promise.all(['backend.log', 'frontend.log', 'redis-console.log', 'tmp/redis.log', 'mysql.log', 'mysql-initialize.log', 'mysql-bootstrap.log'].map(async file => [file, (await readFile(join(dir, file), 'utf8').catch(() => '')).slice(-16000).replaceAll(password, '[temporary password]')])));
    await cleanup().catch(cleanupError => { error.cleanupError = cleanupError; });
    throw error;
  }
}
