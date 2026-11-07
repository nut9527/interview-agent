// 一次性真实 HTTP 环境，不连接开发者的业务数据库。
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, open, rm, realpath } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { parseEnv, findExecutable, capture, stopChild, assertPortFree, waitForHttp, acceptsBackend } from './dev.mjs';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function readLiveEnvironment(envFile = join(root, '.env'), environment = process.env) {
  let contents = '';
  try { contents = readFileSync(envFile, 'utf8'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  // 与开发启动器一致：.env 可选，显式进程配置优先，避免覆盖测试的 Worker 开关。
  return { ...parseEnv(contents), ...environment };
}

export async function liveStack(realModel = true, { environment } = {}) {
  const configured = environment ?? readLiveEnvironment();
  // 配置校验必须早于创建资源，缺少密钥或错误配置不留下临时目录。
  if (realModel && !(configured.DEEPSEEK_API_KEY || configured.AI_API_KEY)) throw new Error('Real model requires configured API key');
  const parent = join(root, '.runtime'); await mkdir(parent, { recursive: true });
  const dir = await mkdtemp(join(parent, 'live-acceptance-'));
  const tmp = join(dir, 'tmp'); await mkdir(tmp);
  const password = randomBytes(20).toString('hex');
  const env = { ...configured, TMP: tmp, TEMP: tmp,
    MYSQL_PWD: password, MYSQL_TEST_LOGIN_FILE: join(tmp, 'no-login'),
    AI_PROVIDER: realModel ? 'deepseek' : 'mock', SERVER_PORT: '18080', SERVER_ADDRESS: '127.0.0.1',
    DEEPSEEK_TIMEOUT_SECONDS: realModel ? '90' : '30',
    SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:13308/live_acceptance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true',
    SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: password,
    QUOTA_AI_PER_MINUTE: '100', QUOTA_AI_UNITS_PER_DAY: '1000', QUOTA_REGISTER_PER_MINUTE: '100',
    SPRING_DOCKER_COMPOSE_ENABLED: 'false' };
  const children = [], logs = [];
  let databaseServer;
  const cleanup = async () => {
    // Windows 限制进程树枚举时仍支持正常停止。
    if (databaseServer?.pid && databaseServer.exitCode === null) {
      await capture(findExecutable('mysqladmin'), ['--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13308','--user=root','shutdown'], { env, timeout:15000 }).catch(() => {});
    }
    for (const child of children.reverse()) {
      await stopChild(child);
      // 受限 Windows 会话可能无法执行 taskkill /T，此处句柄均属于本次任务。
      if (child.exitCode === null && child.signalCode === null) child.kill();
    }
    for (const log of logs) await log.close();
    const actual = await realpath(dir);
    if (!actual.startsWith(await realpath(parent) + sep)) throw new Error('Unsafe cleanup path');
    await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 300 });
  };
  try {
    await assertPortFree(13308); await assertPortFree(18080); await assertPortFree(15173);
    const mysqld = findExecutable('mysqld'), data = join(dir, 'data');
    const init = await capture(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`, `--datadir=${data}`, '--initialize-insecure', `--log-error=${join(dir, 'init.log')}`], { env, cwd: dir, timeout: 120000 });
    if (init.code) throw new Error('MySQL initialization failed');
    const launch = async (exe, args, cwd, filename) => {
      const log = await open(join(dir, filename), 'w'); logs.push(log);
      const child = spawn(exe, args, { env, cwd, windowsHide: true, stdio: ['ignore', log.fd, log.fd] });
      children.push(child); child.on('error', () => {}); return child;
    };
    databaseServer = await launch(mysqld, ['--no-defaults', `--basedir=${dirname(dirname(mysqld))}`, `--datadir=${data}`, `--tmpdir=${tmp}`, '--port=13308', '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin', `--log-error=${join(dir,'mysql.log')}`], dir, 'mysql-console.log');
    const common = ['--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13308','--user=root'];
    let ready = false;
    for (let n=0;n<60;n++) {
      const ping = await capture(findExecutable('mysqladmin'), [...common, 'ping'], { env: {...env, MYSQL_PWD:''}, timeout:3000 });
      if (!ping.code) { ready=true; break; } await new Promise(r=>setTimeout(r,500));
    }
    if (!ready) throw new Error('MySQL readiness timeout');
    await new Promise((done,reject)=>{
      const c=spawn(findExecutable('mysql'),common,{env:{...env,MYSQL_PWD:''},windowsHide:true,stdio:['pipe','ignore','ignore']});
      const timer=setTimeout(()=>{void stopChild(c);reject(new Error('bootstrap timeout'));},15000);
      c.on('close',code=>{clearTimeout(timer);code?reject(new Error('bootstrap failed')):done();});
      c.stdin.end(`ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}'; CREATE DATABASE live_acceptance CHARACTER SET utf8mb4;`);
    });
    const backend = await launch(findExecutable('java'), ['-Djava.io.tmpdir='+tmp,
      ...(realModel && process.platform==='win32'?['-Djavax.net.ssl.trustStoreType=Windows-ROOT','-Djavax.net.ssl.trustStore=NONE']:[]),
      '-jar', process.env.LIVE_BACKEND_JAR || join(root,'backend/target/zhizhida-backend-0.1.0-SNAPSHOT.jar')], join(root,'backend'), 'backend.log');
    // 单次 HTTP 也有超时；数据库真正就绪后再开始验收，避免探活成功掩盖迁移失败。
    await waitForHttp('http://127.0.0.1:18080/api/health/ready', acceptsBackend,
      { timeout: 60000, alive: () => backend.exitCode === null && backend.signalCode === null });
    env.VITE_API_PROXY_TARGET='http://127.0.0.1:18080';
    const frontend = await launch(findExecutable('node'), [join(root,'frontend/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','15173','--strictPort'],join(root,'frontend'),'frontend.log');
    await waitForHttp('http://127.0.0.1:15173', async response => (await response.text()).includes('id="root"'),
      { timeout: 30000, alive: () => frontend.exitCode === null && frontend.signalCode === null });
    return {origin:'http://127.0.0.1:15173',dir,tmp,cleanup};
  } catch(e) { console.error('Live stack failed:',e.message); await cleanup(); throw e; }
}
