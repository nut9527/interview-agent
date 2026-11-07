// 项目独占的本地派生索引数据库；不下载镜像、不接触业务 MySQL、不删除数据库文件。
import { spawn } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, realpath, lstat } from 'node:fs/promises';
import { join, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PROJECT_ROOT, capture, findExecutable, assertPortFree, stopChild } from './dev.mjs';

export const POSTGRES = Object.freeze({ name: 'zhizhida-rag-postgres', image: 'pgvector/pgvector:pg17',
  port: 15432, dimension: 512, database: 'agent_vectors', user: 'agent_vectors' });
const runtime = join(PROJECT_ROOT, '.runtime', 'rag');
const dataDirectory = join(runtime, 'postgres');
const credentialsFile = join(runtime, 'postgres-credentials.json');
const label = 'zhizhida.rag.project';
const projectId = createHash('sha256').update(resolve(PROJECT_ROOT).replaceAll('\\', '/').toLowerCase()).digest('hex');
const sleep = ms => new Promise(done => setTimeout(done, ms));

function normalizedPath(value) {
  // Docker Desktop 把 Windows bind mount 显示为 /run/desktop/mnt/host/d/...。
  return value.replaceAll('\\', '/').replace(/^\/run\/desktop\/mnt\/host\/([a-z])\//i, '$1:/')
    .replace(/^\/host_mnt\/([a-z])\//i, '$1:/').replace(/\/$/, '').toLowerCase();
}

export function assertOwnedContainer(info, expectedData = dataDirectory, expectedProject = projectId) {
  if (info.labels?.[label] !== expectedProject || info.image !== POSTGRES.image)
    throw new Error('同名 pgvector 容器不属于本项目，拒绝接管。');
  const mounts = info.mounts || [];
  if (mounts.length !== 1 || mounts[0].Type !== 'bind' || mounts[0].Destination !== '/var/lib/postgresql/data'
      || normalizedPath(mounts[0].Source) !== normalizedPath(expectedData) || mounts[0].RW !== true)
    throw new Error('pgvector 数据挂载与项目目录不匹配，拒绝操作。');
  const ports = info.ports || {};
  const binding = ports['5432/tcp'];
  if (Object.keys(ports).length !== 1 || binding?.length !== 1
      || binding[0].HostIp !== '127.0.0.1' || binding[0].HostPort !== String(POSTGRES.port))
    throw new Error('pgvector 必须只通过本机 15432 端口访问。');
  if (info.restart !== 'unless-stopped') throw new Error('pgvector 容器的重启策略与项目配置不匹配。');
}

export function createArguments(path = dataDirectory, owner = projectId) {
  if (path.includes(',')) throw new Error('pgvector 项目目录不能含逗号。');
  return ['create', '--name', POSTGRES.name, '--label', `${label}=${owner}`,
    '--restart', 'unless-stopped', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB',
    '-p', `127.0.0.1:${POSTGRES.port}:5432`, '--mount', `type=bind,source=${path},target=/var/lib/postgresql/data`, POSTGRES.image];
}

function validateCredentials(value) {
  if (value.project !== projectId || !/^[a-f0-9]{48}$/.test(value.adminPassword || '')
      || !/^[a-f0-9]{48}$/.test(value.appPassword || ''))
    throw new Error('项目 pgvector 凭据格式无效；请保留现有数据和凭据后人工核对。');
  return value;
}

export function provisionSql(password) {
  if (!/^[a-f0-9]{48}$/.test(password)) throw new Error('Invalid application password');
  return `BEGIN;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS public.knowledge_vectors (
  embedding_id UUID PRIMARY KEY,
  embedding vector(${POSTGRES.dimension}),
  text TEXT NULL,
  metadata JSON NULL
);
DO $dimension$ DECLARE actual TEXT; BEGIN
  SELECT format_type(atttypid, atttypmod) INTO actual FROM pg_attribute
    WHERE attrelid='public.knowledge_vectors'::regclass AND attname='embedding' AND NOT attisdropped;
  IF actual IS DISTINCT FROM 'vector(${POSTGRES.dimension})' THEN
    RAISE EXCEPTION 'Existing embedding dimension does not match the configured model; data was preserved';
  END IF;
END $dimension$;
DO $role$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='${POSTGRES.user}') THEN
    CREATE ROLE ${POSTGRES.user} LOGIN;
  END IF;
END $role$;
ALTER ROLE ${POSTGRES.user} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '${password}';
REVOKE ALL ON DATABASE ${POSTGRES.database} FROM PUBLIC;
REVOKE ALL ON DATABASE ${POSTGRES.database} FROM ${POSTGRES.user};
GRANT CONNECT ON DATABASE ${POSTGRES.database} TO ${POSTGRES.user};
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM ${POSTGRES.user};
GRANT USAGE ON SCHEMA public TO ${POSTGRES.user};
REVOKE ALL ON TABLE public.knowledge_vectors FROM ${POSTGRES.user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.knowledge_vectors TO ${POSTGRES.user};
COMMIT;
`;
}

async function localDirectories() {
  const root = await realpath(PROJECT_ROOT);
  // 逐层核对已有目录，避免通过项目中的符号链接写到项目之外。
  let current = root;
  for (const name of ['.runtime', 'rag']) {
    current = join(current, name);
    await mkdir(current, { recursive: true });
    if (!(await realpath(current)).startsWith(root + sep)) throw new Error('RAG runtime directory is outside the project');
  }
  for (const name of ['docker-cli', 'tmp', 'postgres']) {
    const path = join(runtime, name);
    await mkdir(path, { recursive: true });
    if (!(await realpath(path)).startsWith(root + sep)) throw new Error('RAG data directory is outside the project');
  }
  return { ...process.env, TMP: join(runtime, 'tmp'), TEMP: join(runtime, 'tmp'), DOCKER_CONFIG: join(runtime, 'docker-cli') };
}

async function credentialState(containerExists) {
  const metadata = await lstat(credentialsFile).catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (metadata && (!metadata.isFile() || metadata.isSymbolicLink())) throw new Error('RAG credentials must be an ordinary project file');
  if (metadata) {
    let saved;
    try { saved = JSON.parse(await readFile(credentialsFile, 'utf8')); }
    catch { throw new Error('项目 pgvector 凭据无法读取或 JSON 已损坏；请保留原文件后人工核对。'); }
    return validateCredentials(saved);
  }
  // 数据目录存在时也不能猜测新口令覆盖旧状态。
  const initialized = await lstat(join(dataDirectory, 'PG_VERSION')).then(() => true).catch(error => {
    if (error.code === 'ENOENT') return false;
    throw error;
  });
  if (containerExists || initialized) throw new Error('已有 pgvector 数据缺少本项目凭据，拒绝重置；请恢复 postgres-credentials.json。');
  const value = { project: projectId, adminPassword: randomBytes(24).toString('hex'), appPassword: randomBytes(24).toString('hex') };
  await writeFile(credentialsFile, JSON.stringify(value) + '\n', { flag: 'wx', mode: 0o600 });
  return value;
}

async function dockerClient() {
  const env = await localDirectories();
  const docker = findExecutable('docker', env);
  const command = async (args, timeout = 30000, overrides = {}) => {
    const result = await capture(docker, args, { env: { ...env, ...overrides }, cwd: PROJECT_ROOT, timeout });
    if (result.code !== 0) throw new Error(`pgvector Docker ${args[0]} 失败；请确认 Docker Desktop 已运行且当前进程有访问权限。`);
    return result.output.trim();
  };
  await command(['version', '--format', '{{.Server.Version}}']);
  const existing = await command(['container', 'ls', '--all', '--filter', `name=^/${POSTGRES.name}$`, '--format', '{{.Names}}']);
  let info;
  if (existing) {
    if (existing !== POSTGRES.name) throw new Error('Unexpected pgvector container inventory');
    // 只读取运行信息，避免将容器的环境变量和密码带到日志中。
    const format = '{"labels":{{json .Config.Labels}},"mounts":{{json .Mounts}},"ports":{{json .HostConfig.PortBindings}},"restart":{{json .HostConfig.RestartPolicy.Name}},"running":{{json .State.Running}},"image":{{json .Config.Image}}}';
    info = JSON.parse(await command(['inspect', '--format', format, POSTGRES.name]));
    assertOwnedContainer(info);
  }
  return { docker, env, command, info };
}

async function sqlThroughStdin(docker, env, sql) {
  await new Promise((done, reject) => {
    const child = spawn(docker, ['exec', '-i', POSTGRES.name, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1',
      '-U', 'postgres', '-d', POSTGRES.database], { env, cwd: PROJECT_ROOT, windowsHide: true,
      detached: process.platform !== 'win32', stdio: ['pipe', 'ignore', 'ignore'] });
    // SQL 中含应用密码，既不进入进程参数，也不回显 SQL/错误片段。
    const timer = setTimeout(async () => {
      await stopChild(child).catch(() => {});
      reject(new Error('pgvector schema provisioning timed out (30 seconds)'));
    }, 30000);
    child.on('error', () => { clearTimeout(timer); reject(new Error('pgvector schema provisioning could not start')); });
    child.on('close', code => { clearTimeout(timer); code === 0 ? done() : reject(new Error('pgvector schema provisioning failed; check database version/dimension. Existing data was preserved.')); });
    child.stdin.on('error', () => {});
    child.stdin.end(sql);
  });
}

/** 返回应用连接配置，含密码；调用方应直接写本地 .env，不得整体打印。 */
export async function ensurePostgres() {
  const client = await dockerClient();
  const { docker, env, command, info } = client;
  const credentials = await credentialState(Boolean(info));
  if (!info) {
    // 本地已缓存镜像才启动；脚本不隐式下载或更改 Docker 的全局配置。
    await command(['image', 'inspect', POSTGRES.image, '--format', '{{.Id}}']);
    await assertPortFree(POSTGRES.port);
    await command(createArguments(), 30000, { POSTGRES_PASSWORD: credentials.adminPassword, POSTGRES_DB: POSTGRES.database });
  }
  if (!info?.running) await command(['start', POSTGRES.name]);
  const deadline = Date.now() + 60000;
  let ready = false;
  while (Date.now() < deadline) {
    const result = await capture(docker, ['exec', POSTGRES.name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', POSTGRES.database],
      { env, cwd: PROJECT_ROOT, timeout: 3000 });
    if (result.code === 0) { ready = true; break; }
    await sleep(500);
  }
  if (!ready) throw new Error('pgvector 未在 60 秒内就绪；保留容器与数据供检查。');
  await sqlThroughStdin(docker, env, provisionSql(credentials.appPassword));
  const permissions = await command(['exec', '-e', 'PGPASSWORD', POSTGRES.name, 'psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1',
    '-h', '127.0.0.1', '-U', POSTGRES.user, '-d', POSTGRES.database, '-c',
    "SELECT current_user, has_schema_privilege(current_user,'public','CREATE'), has_table_privilege(current_user,'public.knowledge_vectors','SELECT,INSERT,UPDATE,DELETE')"],
  10000, { PGPASSWORD: credentials.appPassword });
  if (permissions !== `${POSTGRES.user}|f|t`) throw new Error('pgvector 应用用户权限校验失败。');
  return { AGENT_VECTOR_PG_HOST: '127.0.0.1', AGENT_VECTOR_PG_PORT: String(POSTGRES.port),
    AGENT_VECTOR_PG_DATABASE: POSTGRES.database, AGENT_VECTOR_PG_USER: POSTGRES.user,
    AGENT_VECTOR_PG_PASSWORD: credentials.appPassword, AGENT_VECTOR_DIMENSION: String(POSTGRES.dimension) };
}

/** 仅停止本项目的容器；数据与凭据继续保留。 */
export async function stopPostgres() {
  const { command, info } = await dockerClient();
  if (info?.running) await command(['stop', '--time', '10', POSTGRES.name], 20000);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [action = 'help', ...extra] = process.argv.slice(2);
  if (extra.length || !['up', 'stop', 'help', '--help'].includes(action)) throw new Error('Usage: node scripts/rag-postgres.mjs up|stop');
  if (action === 'up') {
    await ensurePostgres();
    console.log(`[rag] pgvector ready at 127.0.0.1:${POSTGRES.port}; ${POSTGRES.dimension} dimensions; credentials remain in project runtime.`);
  } else if (action === 'stop') {
    await stopPostgres();
    console.log('[rag] Project pgvector stopped; data and credentials preserved.');
  } else console.log('node scripts/rag-postgres.mjs up|stop\nUses a cached pgvector:pg17 image and project-local storage; never removes data or changes .env.');
}
