import { execFile, spawn } from 'node:child_process';
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
  return major > 22 || (major === 22 && minor >= 14);
}

export function infrastructureUpArgs(options) {
  return ['up', '-d', '--pull', options.offline ? 'never' : 'missing',
    '--wait', '--wait-timeout', '120', 'mysql', 'redis', 'redis-cache', 'rabbitmq'];
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
  const redisHost = env.REDIS_RATE_HOST || env.REDIS_HOST || '127.0.0.1';
  const redisPort = port(env.REDIS_RATE_PORT || env.REDIS_PORT, 6379, 'REDIS_RATE_PORT');
  if (!options.externalDb && [backendPort, frontendPort].includes(mysqlPort)) {
    throw new Error('本机启动时 MYSQL_PORT、BACKEND_PORT 与 DEV_FRONTEND_PORT 必须使用不同端口。');
  }
  const jdbc = env.SPRING_DATASOURCE_URL || `jdbc:mysql://127.0.0.1:${mysqlPort}/${env.MYSQL_DATABASE || 'zhizhida'}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000`;
  let database;
  if (!jdbc.startsWith('jdbc:mysql://')) throw new Error('SPRING_DATASOURCE_URL 须以 jdbc:mysql:// 开头。');
  try { database = new URL(jdbc.slice('jdbc:'.length)); } catch { throw new Error('SPRING_DATASOURCE_URL 不是有效的 MySQL JDBC 地址。'); }
  if (!database.hostname || database.pathname.length < 2) throw new Error('SPRING_DATASOURCE_URL 须指定 MySQL 主机和数据库名称。');
  if (!options.externalDb && env.SPRING_DATASOURCE_URL) {
    throw new Error('已设置 SPRING_DATASOURCE_URL，请使用 --external-db，避免启动与目标连接无关的 MySQL 容器。');
  }
  const aiProvider = options.mockAi ? 'mock' : env.AI_PROVIDER || 'mock';
  if (!['mock', 'deepseek'].includes(aiProvider)) throw new Error('AI_PROVIDER 只能为 mock 或 deepseek。');
  if (aiProvider === 'deepseek' && !(env.DEEPSEEK_API_KEY || env.AI_API_KEY)) {
    throw new Error('DeepSeek 模式未设置密钥。可加 --mock-ai 使用本地 AI 模拟，或在 .env 配置密钥。');
  }
  const dispatch = env.RUN_DISPATCH_MODE || (options.externalDb ? 'database' : 'rabbitmq');
  const mcpEnabled = env.AGENT_MCP_REFERENCE_ENABLED || 'true';
  const managedMcp = mcpEnabled.toLowerCase() === 'true' && !env.AGENT_MCP_REFERENCE_URL;
  const mcpPort = managedMcp ? port(env.MCP_PORT, 18091, 'MCP_PORT') : undefined;
  const localPorts = [backendPort, frontendPort];
  if (!options.externalDb) {
    localPorts.push(mysqlPort, redisPort,
      port(env.REDIS_CACHE_PORT, 6380, 'REDIS_CACHE_PORT'), port(env.RABBITMQ_PORT, 5672, 'RABBITMQ_PORT'),
      port(env.RABBITMQ_MANAGEMENT_PORT, 15672, 'RABBITMQ_MANAGEMENT_PORT'));
  }
  if (managedMcp) localPorts.push(mcpPort);
  if (new Set(localPorts).size !== localPorts.length) {
    throw new Error('应用与依赖服务的本机监听端口冲突，请检查 MCP_PORT、Redis、RabbitMQ 与前后端设置。');
  }
  return { backendPort, frontendPort, mcpPort, databaseHost: database.hostname.replace(/^\[|\]$/g, ''), databasePort: Number(database.port || 3306),
    redisHost, redisPort,
    childEnv: { ...env, AI_PROVIDER: aiProvider, SPRING_DOCKER_COMPOSE_ENABLED: 'false',
      QUESTION_CACHE_ENABLED: env.QUESTION_CACHE_ENABLED || String(!options.externalDb),
      REDIS_RATE_LIMIT_ENABLED: env.REDIS_RATE_LIMIT_ENABLED || String(!options.externalDb),
      RUN_DISPATCH_MODE: dispatch,
      KNOWLEDGE_DISPATCH_MODE: env.KNOWLEDGE_DISPATCH_MODE || (dispatch === 'rabbitmq' ? 'rabbitmq' : 'local'),
      KNOWLEDGE_IMPORT_SHARED_DIRECTORY: env.KNOWLEDGE_IMPORT_SHARED_DIRECTORY || join(PROJECT_ROOT, '.runtime', 'knowledge-shared'),
      AGENT_MCP_REFERENCE_ENABLED: mcpEnabled,
      AGENT_MCP_REFERENCE_URL: env.AGENT_MCP_REFERENCE_URL || `http://127.0.0.1:${mcpPort || 18091}/mcp`,
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
    const killTree = (exe, args) => new Promise((done) => {
      execFile(exe, args, { timeout: 4000, windowsHide: true }, (error) => done(!error));
    });
    const pid = String(child.pid);
    const killed = await killTree('taskkill.exe', ['/PID', pid, '/T', '/F']);
    if (!killed && child.exitCode === null && child.signalCode === null) {
      // 受限会话可能拒绝 taskkill 的树枚举，JDK ProcessHandle 走另一条系统调用路径。
      const helper = join(PROJECT_ROOT, 'scripts', 'StopOwnedProcessTree.java');
      if (!await killTree('java', ['-XX:-UsePerfData', helper, pid])) {
        console.warn(`无法确认进程 ${pid} 的整棵子进程树已退出。`);
        if (child.exitCode === null && child.signalCode === null) child.kill();
      }
    }
    for (let attempt = 0; attempt < 20 && child.exitCode === null && child.signalCode === null; attempt++) await sleep(50);
    if (child.exitCode === null && child.signalCode === null) throw new Error(`等待进程 ${pid} 退出超时。`);
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
      // 等待本次启动的子进程树退出，再让调用方删除临时资源。
      try { await stopChild(child); } catch (stopError) { reject(stopError); return; }
      reject(error);
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
  return assertServiceReachable(host, number, 'MySQL', '数据库');
}

export function assertRedisReachable(host, number) {
  return assertServiceReachable(host, number, 'Redis', '协调 Redis（即使关闭提交限流也必需）');
}

function assertServiceReachable(host, number, service, requirement) {
  return new Promise((done, reject) => {
    const socket = createConnection({ host, port: number });
    const fail = () => { socket.destroy(); reject(new Error(`${service} ${host}:${number} 不可连接；请先启动${requirement}并核对连接配置。`)); };
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
  throw new Error(`等待服务就绪超时：${url}；检查日志中的数据库、Redis 连接、Flyway 或端口错误。`);
}

export const acceptsBackend = async (response) => {
  const body = await response.json();
  return body.success === true && body.data?.service === 'zhizhida-backend' && body.data?.database === 'up';
};

export async function main(args = process.argv.slice(2)) {
  const options = parseArgs(args);
  if (options.help) {
    console.log('用法：node scripts/dev.mjs [start|check] [--external-db] [--offline] [--install] [--mock-ai]\n默认启动 MySQL、Redis、RabbitMQ、本地 MCP 资料服务、后端和 Vite。\nCtrl+C 停止本次源码进程，保留容器和数据。check 仅检查环境；--external-db 使用已有 MySQL 和协调 Redis 并跳过容器启动，Redis 地址通过 REDIS_RATE_HOST/PORT（兼容 REDIS_HOST/PORT）设置；--install 按锁文件重装前端依赖。');
    return;
  }
  if (!supportedNode(process.version)) throw new Error('需要 Node.js 22.14 或更高版本。');
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
  for (const number of [settings.backendPort, settings.frontendPort, settings.mcpPort].filter(Boolean)) {
    try { await assertPortFree(number); } catch (error) { errors.push(error.message); }
  }
  let docker;
  if (options.externalDb) {
    try { await assertDatabaseReachable(settings.databaseHost, settings.databasePort); } catch (error) { errors.push(error.message); }
    try { await assertRedisReachable(settings.redisHost, settings.redisPort); } catch (error) { errors.push(error.message); }
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
      console.log('[依赖] 正在启动配置的 Compose 服务并等待健康检查……');
      await stage('mysql', docker,
        ['compose', '--progress', 'plain', '--project-directory', PROJECT_ROOT, '-f', join(PROJECT_ROOT, 'docker-compose.yml'),
          ...infrastructureUpArgs(options)], PROJECT_ROOT, { MYSQL_IMAGE: mysqlImage });
    }
    await assertDatabaseReachable(settings.databaseHost, settings.databasePort);
    await assertRedisReachable(settings.redisHost, settings.redisPort);
    // 每次启动使用全新目录；不复用 IDE 可能写入的错误类，也不清理已有 target 中的报告。
    const target = join(runDir, 'backend');
    await stage('build', maven, ['-B', ...(options.offline ? ['-o'] : []),
      `-Dzhizhida.build.directory=${target}`, '-DskipTests', 'package'], join(PROJECT_ROOT, 'backend'));
    const jars = readdirSync(target).filter((name) => /^zhizhida-backend-.*\.jar$/.test(name));
    if (jars.length !== 1) throw new Error(`本次构建的可执行 JAR 不唯一，请检查 ${target}；脚本不会删除已有文件。`);
    if (settings.mcpPort) {
      const mcp = launch('mcp-reference', process.execPath, ['services/mcp-reference/server.mjs'], PROJECT_ROOT, false,
        { MCP_BIND_HOST: '127.0.0.1', MCP_PORT: String(settings.mcpPort) });
      void mcp.done.then(() => { if (!stopping) console.warn('[MCP] 资料服务已退出，延伸阅读暂不可用。'); },
        () => console.warn('[MCP] 资料服务启动失败，请检查 mcp-reference.log。'));
    }
    const backend = launch('backend', java, ['-jar', join(target, jars[0])], join(PROJECT_ROOT, 'backend'), true);
    console.log('[等待] 后端启动、Flyway 迁移与数据库就绪……');
    await waitForHttp(`http://127.0.0.1:${settings.backendPort}/api/health/ready`, acceptsBackend,
      { alive: () => !stopping && !backend.failed && backend.exitCode === null && !backend.signalCode });
    const frontend = launch('frontend', process.execPath, [vite, '--host', '127.0.0.1', '--port', String(settings.frontendPort)],
      join(PROJECT_ROOT, 'frontend'), true);
    await waitForHttp(`http://127.0.0.1:${settings.frontendPort}/api/health/ready`, acceptsBackend,
      { alive: () => !stopping && !frontend.failed && frontend.exitCode === null && !frontend.signalCode, timeout: 30000 });
    console.log(`[就绪] 页面 http://127.0.0.1:${settings.frontendPort}\n[就绪] API 文档 http://127.0.0.1:${settings.backendPort}/doc.html\nCtrl+C 停止本次前后端；依赖服务和数据保留。`);
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
