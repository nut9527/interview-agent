import { spawn } from 'node:child_process';
import { existsSync, readFileSync, mkdirSync, readdirSync, createWriteStream } from 'node:fs';
import { createServer, createConnection } from 'node:net';
import { dirname, join, delimiter, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareMysqlImage } from './mysql-startup.mjs';
import { monitorStage } from './startup-progress.mjs';

export const PROJECT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PNPM_VERSION = '9.15.9';
const sleep = (ms) => new Promise((resolveSleep) => setTimeout(resolveSleep, ms));

export function parseArgs(args) {
  const allowed = new Set(['start', 'check', '--external-db', '--offline', '--install', '--mock-ai', '--help']);
  for (const arg of args) if (!allowed.has(arg)) throw new Error(`未知参数：${arg}；使用 --help 查看用法。`);
  if (args.includes('start') && args.includes('check')) throw new Error('start 与 check 不能同时使用。');
  return { check: args.includes('check'), externalDb: args.includes('--external-db'), offline: args.includes('--offline'),
    install: args.includes('--install'), mockAi: args.includes('--mock-ai'), help: args.includes('--help') };
}

// 只读取键值，不执行 Shell，不展开 ${...}；进程环境变量优先于 .env。
export function parseEnv(text) {
  const result = {};
  for (const [index, source] of text.replace(/^\uFEFF/, '').split(/\r?\n/).entries()) {
    const line = source.trim();
    if (!line || line.startsWith('#')) continue;
    const pair = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!pair) throw new Error(`.env 第 ${index + 1} 行不是 KEY=value 格式。`);
    let value = pair[2];
    if (value.startsWith('"') || value.startsWith("'")) {
      const end = value.lastIndexOf(value[0]);
      if (end === 0 || !/^\s*(?:#.*)?$/.test(value.slice(end + 1))) {
        throw new Error(`.env 第 ${index + 1} 行引号不完整。`);
      }
      value = value.slice(1, end);
    } else value = value.replace(/\s+#.*$/, '').trim();
    result[pair[1]] = value;
  }
  return result;
}

export function supportedNode(version) {
  const [major, minor] = version.replace(/^v/, '').split('.').map(Number);
  return major === 20 ? minor >= 19 : major === 22 ? minor >= 12 : major > 22;
}

function port(value, fallback, name) {
  const number = Number(value || fallback);
  if (!Number.isInteger(number) || number < 1 || number > 65535) throw new Error(`${name} 必须为 1-65535 的整数。`);
  return number;
}

export function makeSettings(env, options) {
  const backendPort = port(env.BACKEND_PORT, 8080, 'BACKEND_PORT');
  const frontendPort = port(env.DEV_FRONTEND_PORT, 5173, 'DEV_FRONTEND_PORT');
  if (backendPort === frontendPort) throw new Error('后端和前端不能使用同一端口。');
  const mysqlPort = port(env.MYSQL_PORT, 3307, 'MYSQL_PORT');
  const jdbc = env.SPRING_DATASOURCE_URL || `jdbc:mysql://127.0.0.1:${mysqlPort}/${env.MYSQL_DATABASE || 'zhizhida'}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000`;
  let database;
  try { database = new URL(jdbc.replace(/^jdbc:/, '')); } catch { throw new Error('SPRING_DATASOURCE_URL 不是有效的 MySQL JDBC 地址。'); }
  if (database.protocol !== 'mysql:') throw new Error('本项目需要 MySQL JDBC 地址。');
  if (!options.externalDb && env.SPRING_DATASOURCE_URL) {
    throw new Error('已设置 SPRING_DATASOURCE_URL，请使用 --external-db，避免启动与目标连接无关的 MySQL 容器。');
  }
  const aiProvider = options.mockAi ? 'mock' : env.AI_PROVIDER || 'mock';
  if (!['mock', 'deepseek'].includes(aiProvider)) throw new Error('AI_PROVIDER 只能为 mock 或 deepseek。');
  if (aiProvider === 'deepseek' && !(env.DEEPSEEK_API_KEY || env.AI_API_KEY)) {
    throw new Error('DeepSeek 模式未设置密钥。可加 --mock-ai 使用本地 AI 模拟，或在 .env 配置密钥。');
  }
  return { backendPort, frontendPort, databaseHost: database.hostname.replace(/^\[|\]$/g, ''), databasePort: Number(database.port || 3306),
    childEnv: { ...env, AI_PROVIDER: aiProvider, SPRING_DOCKER_COMPOSE_ENABLED: 'false',
      SPRING_DATASOURCE_URL: jdbc,
      SPRING_DATASOURCE_USERNAME: env.SPRING_DATASOURCE_USERNAME || env.MYSQL_USER || 'zhizhida',
      SPRING_DATASOURCE_PASSWORD: env.SPRING_DATASOURCE_PASSWORD ?? (env.MYSQL_PASSWORD || 'zhizhida'),
      SERVER_PORT: String(backendPort), SERVER_ADDRESS: '127.0.0.1',
      VITE_API_PROXY_TARGET: `http://127.0.0.1:${backendPort}`,
      npm_config_cache: join(PROJECT_ROOT, '.cache', 'npm'), COREPACK_HOME: join(PROJECT_ROOT, '.cache', 'corepack') } };
}

export function findExecutable(name, env = process.env) {
  const suffixes = process.platform === 'win32' ? (env.PATHEXT || '.EXE;.CMD;.BAT').split(';') : [''];
  for (const directory of (env.PATH || env.Path || '').split(delimiter)) {
    for (const suffix of suffixes) {
      const candidate = join(directory.replace(/^"|"$/g, ''), name + suffix.toLowerCase());
      if (existsSync(candidate)) return candidate;
    }
  }
  throw new Error(`找不到 ${name}，请安装项目所需工具并确认当前终端 PATH。`);
}

function startProcess(executable, args, options) {
  if (process.platform === 'win32' && /\.(cmd|bat)$/i.test(executable)) {
    const quote = (value) => {
      // cmd 会展开 % / !，禁止这些字符进入构造的命令；密钥只通过环境传递。
      if (/["%!\r\n]/.test(value)) throw new Error('Windows 命令路径或参数含不支持的引号/展开字符。');
      return `"${value}"`;
    };
    const command = `"${[executable, ...args].map(quote).join(' ')}"`;
    return spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', command],
      { ...options, windowsHide: true, windowsVerbatimArguments: true });
  }
  return spawn(executable, args, { ...options, windowsHide: true });
}

export async function stopChild(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
  if (process.platform === 'win32') {
    // PID 只来自本次 spawn 的活跃进程对象；不按端口或进程名杀进程。
    await new Promise((done) => {
      const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      killer.on('error', done);
      killer.on('exit', done);
    });
  } else {
    try { process.kill(-child.pid, 'SIGTERM'); } catch { /* 已退出 */ }
    await sleep(500);
    if (child.exitCode === null && child.signalCode === null) {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { /* 已退出 */ }
    }
  }
}

export function capture(executable, args, { cwd = PROJECT_ROOT, env = process.env, timeout = 15000, signal, onOutput } = {}) {
  return new Promise((done, reject) => {
    if (signal?.aborted) { reject(new Error('命令已取消。')); return; }
    const child = startProcess(executable, args, { cwd, env, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    let settled = false;
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
    const stop = async (error) => {
      if (settled) return;
      settled = true;
      cleanup();
      // Wait for our child tree to stop before the caller removes its temporary resources.
      try { await stopChild(child); } finally { reject(error); }
    };
    const abort = () => { void stop(new Error('命令已取消。')); };
    const timer = setTimeout(() => { void stop(new Error('环境检查命令超时。')); }, timeout);
    signal?.addEventListener('abort', abort, { once: true });
    for (const stream of [child.stdout, child.stderr]) stream.on('data', (data) => {
      output = (output + data).slice(-24000);
      onOutput?.(data);
    });
    child.on('error', (error) => { if (!settled) { settled = true; cleanup(); reject(error); } });
    child.on('close', (code) => { if (!settled) { settled = true; cleanup(); done({ code, output }); } });
  });
}

export function assertPortFree(number, host = '127.0.0.1') {
  return new Promise((done, reject) => {
    const server = createServer();
    server.once('error', () => reject(new Error(`端口 ${number} 已被占用或不可绑定；请修改对应端口配置，不会终止现有进程。`)));
    server.listen(number, host, () => server.close(done));
  });
}

export function assertDatabaseReachable(host, number) {
  return new Promise((done, reject) => {
    const socket = createConnection({ host, port: number });
    const fail = () => { socket.destroy(); reject(new Error(`MySQL ${host}:${number} 不可连接；请先启动数据库并核对连接配置。`)); };
    socket.setTimeout(2000, fail);
    socket.once('error', fail);
    socket.once('connect', () => { socket.destroy(); done(); });
  });
}

export async function waitForHttp(url, accepts, { timeout = 90000, alive = () => true, interval = 500 } = {}) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (!alive()) throw new Error('服务进程已退出，请查看本次启动日志。');
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (response.ok && await accepts(response)) return;
    } catch { /* 未就绪，继续等到截止时间 */ }
    await sleep(interval);
  }
  throw new Error(`等待服务就绪超时：${url}；检查日志中的数据库连接、Flyway 或端口错误。`);
}

export const acceptsBackend = async (response) => {
  const body = await response.json();
  return body.success === true && body.data?.service === 'zhizhida-backend' && body.data?.database === 'up';
};

export async function main(args = process.argv.slice(2)) {
  const options = parseArgs(args);
  if (options.help) {
    console.log('用法：node scripts/dev.mjs [start|check] [--external-db] [--offline] [--install] [--mock-ai]\n默认启动 Compose MySQL → 编译后端 → 等待就绪 → 启动 Vite。Ctrl+C 停止本次前后端，保留 MySQL 和数据。\ncheck 仅检查环境；--external-db 连接已有 MySQL；--install 按锁文件重装前端依赖。');
    return;
  }
  if (!supportedNode(process.version)) throw new Error('需要 Node 20.19+（20.x）或 22.12+；不支持 Node 21。');
  const envFile = join(PROJECT_ROOT, '.env');
  const env = { ...(existsSync(envFile) ? parseEnv(readFileSync(envFile, 'utf8')) : {}), ...process.env };
  const settings = makeSettings(env, options);
  const java = findExecutable('java', env);
  findExecutable('javac', env);
  const maven = findExecutable('mvn', env);
  const javaVersion = await capture(java, ['-version'], { env: settings.childEnv });
  const major = Number(/version "(\d+)/.exec(javaVersion.output)?.[1]);
  if (javaVersion.code !== 0 || major !== 21) throw new Error('需要 JDK 21；请在当前终端选择 Java 21。');
  const mvnVersion = await capture(maven, ['--version'], { env: settings.childEnv });
  if (mvnVersion.code !== 0) throw new Error('Maven 无法启动，请检查 JAVA_HOME 与 JDK 配置。');
  const mavenJava = Number(/Java version:\s*(\d+)/.exec(mvnVersion.output)?.[1]);
  if (mavenJava !== 21) throw new Error('Maven 的 JAVA_HOME 必须指向 JDK 21，请在当前终端选择正确 JDK。');
  console.log(`[检查] Node ${process.version}，Java ${major}，Maven Java ${mavenJava}`);
  const errors = [];
  for (const number of [settings.backendPort, settings.frontendPort]) {
    try { await assertPortFree(number); } catch (error) { errors.push(error.message); }
  }
  let docker;
  if (options.externalDb) {
    try { await assertDatabaseReachable(settings.databaseHost, settings.databasePort); } catch (error) { errors.push(error.message); }
  } else {
    try {
      docker = findExecutable('docker', env);
      if ((await capture(docker, ['info', '--format', '{{.ServerVersion}}'], { env: settings.childEnv })).code !== 0) {
        errors.push('Docker 引擎未运行。请启动 Docker Desktop，或通过 --external-db 使用已有 MySQL。');
      } else if ((await capture(docker, ['compose', 'version', '--short'], { env: settings.childEnv })).code !== 0) {
        errors.push('Docker Compose 插件不可用。');
      }
    } catch { errors.push('Docker 不可用；请检查安装，或通过 --external-db 使用已有 MySQL。'); }
  }
  const vite = join(PROJECT_ROOT, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js');
  const install = options.install || !existsSync(vite);
  let npm;
  if (install) {
    try { npm = findExecutable('npm', env); } catch (error) { errors.push(error.message); }
    console.log(`[检查] 前端依赖${options.check ? '需要安装' : '将按锁文件安装'}（pnpm ${PNPM_VERSION}，项目内缓存）`);
  }
  if (errors.length) throw new Error(errors.join('\n'));
  if (options.check) { console.log('[检查] 环境预检通过；尚未启动服务或验证数据库凭据。'); return; }

  const runDir = join(PROJECT_ROOT, '.runtime', new Date().toISOString().replace(/[:.]/g, '-') + '-' + process.pid);
  mkdirSync(runDir, { recursive: true });
  console.log(`[日志] ${runDir}`);
  const children = new Set();
  let stopping = false;
  let cleanupPromise;
  let unexpectedExit;
  const stopped = new Promise((done) => { unexpectedExit = done; });
  const cleanup = () => {
    if (cleanupPromise) return cleanupPromise;
    stopping = true;
    cleanupPromise = Promise.all([...children].map(stopChild));
    return cleanupPromise;
  };
  const signalHandler = () => { void cleanup().then(() => unexpectedExit('interrupt')); };
  process.on('SIGINT', signalHandler);
  process.on('SIGTERM', signalHandler);
  function launch(label, executable, commandArgs, cwd, service = false, extraEnv = {}) {
    if (stopping) throw new Error('启动已取消。');
    const log = createWriteStream(join(runDir, `${label}.log`));
    const child = startProcess(executable, commandArgs, { cwd, env: { ...settings.childEnv, ...extraEnv },
      detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    children.add(child);
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    child.done = new Promise((done, reject) => {
      child.once('error', (error) => { child.failed = true; reject(error); });
      child.once('close', (code) => { log.end(); children.delete(child); done(code); if (service && !stopping) unexpectedExit(label); });
    });
    // 服务错误在等待就绪或主循环处理，防止未处理的 Promise rejection。
    if (service) child.done.catch(() => unexpectedExit(label));
    return child;
  }
  async function stage(label, executable, commandArgs, cwd, extraEnv = {}) {
    const logPath = join(runDir, label + '.log');
    console.log(`[启动] ${label}，日志：${logPath}`);
    const child = launch(label, executable, commandArgs, cwd, false, extraEnv);
    await monitorStage(child, { label, logPath, env: settings.childEnv, stop: stopChild });
  }
  try {
    if (install) await stage('install', npm, ['exec', '--yes', ...(options.offline ? ['--offline'] : []),
      `--package=pnpm@${PNPM_VERSION}`, '--', 'pnpm', 'install', '--frozen-lockfile',
      '--store-dir', join(PROJECT_ROOT, '.pnpm-store'), ...(options.offline ? ['--offline'] : [])], join(PROJECT_ROOT, 'frontend'), { CI: 'true' });
    if (!options.externalDb) {
      const mysqlImage = await prepareMysqlImage({ env, offline: options.offline,
        inspect: async (image) => (await capture(docker, ['image', 'inspect', image, '--format', '{{.Id}}'], { env: settings.childEnv })).code === 0,
        pull: (image, index) => stage(`mysql-image-${index + 1}`, docker, ['pull', image], PROJECT_ROOT),
        report: console.log, cancelled: () => stopping });
      console.log('[数据库] 镜像已就绪，正在创建/启动 MySQL 并等待健康检查……');
      await stage('mysql', docker,
        ['compose', '--progress', 'plain', '--project-directory', PROJECT_ROOT, '-f', join(PROJECT_ROOT, 'docker-compose.yml'),
          'up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '120', 'mysql'], PROJECT_ROOT, { MYSQL_IMAGE: mysqlImage });
    }
    await assertDatabaseReachable(settings.databaseHost, settings.databasePort);
    await stage('build', maven, ['-B', ...(options.offline ? ['-o'] : []), '-DskipTests', 'package'], join(PROJECT_ROOT, 'backend'));
    const target = join(PROJECT_ROOT, 'backend', 'target');
    const jars = readdirSync(target).filter((name) => /^zhizhida-backend-.*\.jar$/.test(name));
    if (jars.length !== 1) throw new Error('target 中可执行 JAR 不唯一，请移走过期版本后重试；脚本不会删除已有文件。');
    const backend = launch('backend', java, ['-jar', join(target, jars[0])], join(PROJECT_ROOT, 'backend'), true);
    console.log('[等待] 后端启动、Flyway 迁移与数据库就绪……');
    await waitForHttp(`http://127.0.0.1:${settings.backendPort}/api/health/ready`, acceptsBackend,
      { alive: () => !stopping && !backend.failed && backend.exitCode === null && !backend.signalCode });
    const frontend = launch('frontend', process.execPath, [vite, '--host', '127.0.0.1', '--port', String(settings.frontendPort)],
      join(PROJECT_ROOT, 'frontend'), true);
    await waitForHttp(`http://127.0.0.1:${settings.frontendPort}/api/health/ready`, acceptsBackend,
      { alive: () => !stopping && !frontend.failed && frontend.exitCode === null && !frontend.signalCode, timeout: 30000 });
    console.log(`[就绪] 页面 http://127.0.0.1:${settings.frontendPort}\n[就绪] API 文档 http://127.0.0.1:${settings.backendPort}/doc.html\nCtrl+C 停止本次前后端；MySQL 和数据保留。`);
    const reason = await stopped;
    if (reason !== 'interrupt') throw new Error(`${reason} 意外退出，正在停止本次启动的其他服务。`);
  } finally {
    await cleanup();
    process.off('SIGINT', signalHandler);
    process.off('SIGTERM', signalHandler);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(`[启动失败] ${error.message}`); process.exitCode = 1; });
}
