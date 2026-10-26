import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { parseArgs, parseEnv, makeSettings, supportedNode, assertPortFree, assertDatabaseReachable,
  waitForHttp, acceptsBackend, stopChild, capture, findExecutable } from './dev.mjs';

const options = parseArgs([]);
test('CLI rejects misspelled options and conflicting commands', () => {
  assert.throws(() => parseArgs(['--external-database']));
  assert.throws(() => parseArgs(['start', 'check']));
  assert.equal(parseArgs(['check', '--external-db', '--offline']).externalDb, true);
});

test('dotenv handles BOM, comments, quotes and values containing equals without executing them', () => {
  assert.deepEqual(parseEnv('\uFEFF# ignored\r\nKEY=a=b#c\nEMPTY=\nexport TEXT="hello world" # x\nRAW=\'$(echo no)\'\nPORT=8080 # comment'),
    { KEY: 'a=b#c', EMPTY: '', TEXT: 'hello world', RAW: '$(echo no)', PORT: '8080' });
});

test('invalid dotenv errors include only line number, not secret contents', () => {
  assert.throws(() => parseEnv('KEY="secret-value'), (error) => !error.message.includes('secret-value'));
  assert.throws(() => parseEnv('secret-value-without-equals'), (error) => !error.message.includes('secret-value'));
});

test('supported Node versions reflect Vite engine requirements', () => {
  for (const version of ['20.19.0', '22.12.0', '24.0.0']) assert.equal(supportedNode(version), true);
  for (const version of ['18.20.0', '20.18.9', '21.9.0', '22.11.0']) assert.equal(supportedNode(version), false);
});

test('local backend and proxy share custom port, Compose frontend port is independent', () => {
  const settings = makeSettings({ BACKEND_PORT: '8181', DEV_FRONTEND_PORT: '5188', FRONTEND_PORT: '80', MYSQL_PORT: '3310' }, options);
  assert.equal(settings.frontendPort, 5188);
  assert.equal(settings.childEnv.SERVER_PORT, '8181');
  assert.equal(settings.childEnv.VITE_API_PROXY_TARGET, 'http://127.0.0.1:8181');
  assert.equal(settings.databasePort, 3310);
  assert.equal(settings.childEnv.SPRING_DOCKER_COMPOSE_ENABLED, 'false');
});

test('invalid, equal or privileged-looking malformed port input is rejected', () => {
  for (const value of ['0', '-1', '65536', '8080&echo']) assert.throws(() => makeSettings({ BACKEND_PORT: value }, options));
  assert.throws(() => makeSettings({ BACKEND_PORT: '5173' }, options));
});

test('external database overrides and credentials are passed in environment only', () => {
  const settings = makeSettings({ SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:3306/test',
    SPRING_DATASOURCE_USERNAME: 'app', SPRING_DATASOURCE_PASSWORD: 'local-test', MYSQL_PASSWORD: 'ignored' }, { ...options, externalDb: true });
  assert.equal(settings.databasePort, 3306);
  assert.equal(settings.childEnv.SPRING_DATASOURCE_PASSWORD, 'local-test');
  assert.throws(() => makeSettings({ SPRING_DATASOURCE_URL: 'jdbc:mysql://localhost:3306/test' }, options));
});

test('mock mode is explicit and preserves original configuration', () => {
  const original = { AI_PROVIDER: 'deepseek' };
  assert.throws(() => makeSettings(original, options), /密钥/);
  assert.equal(makeSettings(original, { ...options, mockAi: true }).childEnv.AI_PROVIDER, 'mock');
  assert.equal(original.AI_PROVIDER, 'deepseek');
  assert.equal(makeSettings({}, options).childEnv.AI_PROVIDER, 'mock');
});

async function serve(handler, run) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try { await run(server.address().port); }
  finally { server.closeAllConnections(); await new Promise((done) => server.close(done)); }
}

test('occupied port is rejected without stopping existing service', async () => {
  await serve((_, response) => response.end('existing'), async (port) => {
    await assert.rejects(assertPortFree(port), /端口/);
    assert.equal(await (await fetch(`http://127.0.0.1:${port}`)).text(), 'existing');
    await assertDatabaseReachable('127.0.0.1', port); // 仅验证 TCP，不冒充 MySQL 凭据校验。
  });
});

test('readiness waits for database up, not merely any HTTP 200', async () => {
  let attempts = 0;
  await serve((_, response) => {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify(++attempts < 3 ? { success: true, data: { service: 'another-service' } }
      : { success: true, data: { service: 'zhizhida-backend', database: 'up' } }));
  }, async (port) => {
    await waitForHttp(`http://127.0.0.1:${port}`, acceptsBackend, { interval: 5, timeout: 2000 });
    assert.equal(attempts, 3);
  });
});

test('permanent 503 and exited process fail with a bounded wait', async () => {
  await serve((_, response) => { response.statusCode = 503; response.end(); }, async (port) => {
    await assert.rejects(waitForHttp(`http://127.0.0.1:${port}`, acceptsBackend, { interval: 5, timeout: 80 }), /超时/);
    await assert.rejects(waitForHttp(`http://127.0.0.1:${port}`, acceptsBackend, { alive: () => false }), /退出/);
  });
});

test('child command uses argument array and captures nonzero exit', async () => {
  const result = await capture(process.execPath, ['-e', 'console.log(process.argv[1]);process.exitCode=3', 'a & b 中文']);
  assert.equal(result.code, 3);
  assert.match(result.output, /a & b 中文/);
});

test('capture cancels its child and streams diagnostic output before cleanup', async () => {
  const controller = new AbortController();
  let output = '';
  await assert.rejects(capture(process.execPath, ['-e', 'console.log("ready"); setInterval(() => {}, 1000)'], {
    signal: controller.signal,
    onOutput: (data) => { output += data; controller.abort(); },
    timeout: 5000,
  }), /取消/);
  assert.match(output, /ready/);
});

test('cleanup stops only the spawned service, including its child process', async () => {
  const child = spawn(process.execPath, ['-e', `
    const {spawn}=require('node:child_process');
    const {createServer}=require('node:net');
    const grandchild=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
    const server=createServer();server.listen(0,'127.0.0.1',()=>console.log(JSON.stringify({port:server.address().port,grandchild:grandchild.pid})));
    setInterval(()=>{},1000);
  `], { detached: process.platform !== 'win32', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    const [chunk] = await once(child.stdout, 'data');
    const { port, grandchild } = JSON.parse(chunk.toString().trim());
    await stopChild(child);
    await assertPortFree(port);
    assert.throws(() => process.kill(grandchild, 0), /ESRCH/);
  } finally { await stopChild(child); }
});

test('Windows batch launcher correctly invokes Maven without a visible window', { skip: process.platform !== 'win32' }, async () => {
  const result = await capture(findExecutable('mvn'), ['--version']);
  assert.equal(result.code, 0);
  assert.match(result.output, /Apache Maven/);
});
