import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { PROJECT_ROOT, acceptsBackend, capture, findExecutable, stopChild, waitForHttp } from './dev.mjs';

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
}

async function freePort() {
  const temporary = createServer();
  const port = await listen(temporary);
  await new Promise((done) => temporary.close(done));
  return port;
}

test('real Vite serves page, proxies custom backend port, and refuses occupied port', { timeout: 45000 }, async () => {
  const backend = createServer((_, response) => {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify({ success: true, data: { service: 'zhizhida-backend', database: 'up', fixture: true } }));
  });
  const backendPort = await listen(backend);
  const frontendPort = await freePort();
  const vite = join(PROJECT_ROOT, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js');
  const env = { ...process.env, VITE_API_PROXY_TARGET: `http://127.0.0.1:${backendPort}` };
  const child = spawn(process.execPath, [vite, '--port', String(frontendPort)], {
    cwd: join(PROJECT_ROOT, 'frontend'), env, windowsHide: true,
    detached: process.platform !== 'win32', stdio: 'ignore',
  });
  try {
    await waitForHttp(`http://127.0.0.1:${frontendPort}/api/health/ready`, acceptsBackend,
      { timeout: 20000, alive: () => child.exitCode === null && !child.signalCode });
    const page = await (await fetch(`http://127.0.0.1:${frontendPort}`)).text();
    assert.match(page, /id="root"/);
    const proxied = await (await fetch(`http://127.0.0.1:${frontendPort}/api/health/ready`)).json();
    assert.equal(proxied.data.fixture, true);
    const duplicate = await capture(process.execPath, [vite, '--port', String(frontendPort)], { cwd: join(PROJECT_ROOT, 'frontend'), env });
    assert.notEqual(duplicate.code, 0);
    assert.match(duplicate.output, /already in use/);
    assert.equal((await fetch(`http://127.0.0.1:${frontendPort}`)).status, 200);
  } finally {
    await stopChild(child);
    backend.closeAllConnections();
    await new Promise((done) => backend.close(done));
  }
});

test('external-db preflight works from backend directory and does not need Docker', { timeout: 20000 }, async () => {
  const databaseFixture = createServer();
  const databasePort = await listen(databaseFixture);
  try {
    const result = await capture(process.execPath, [join(PROJECT_ROOT, 'scripts', 'dev.mjs'), 'check', '--external-db', '--mock-ai'], {
      cwd: join(PROJECT_ROOT, 'backend'),
      env: { ...process.env, BACKEND_PORT: String(await freePort()), DEV_FRONTEND_PORT: String(await freePort()),
        SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${databasePort}/tcp_fixture_only` },
    });
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /环境预检通过/);
    assert.match(result.output, /尚未启动服务或验证数据库凭据/);
  } finally {
    databaseFixture.closeAllConnections();
    await new Promise((done) => databaseFixture.close(done));
  }
});

test('container Java health probe compiles for Java 17 and exits on 200/503', { timeout: 25000 }, async () => {
  const classes = join(PROJECT_ROOT, 'backend', 'target', 'docker-healthcheck');
  mkdirSync(classes, { recursive: true });
  const compiled = await capture(findExecutable('javac'), ['--release', '17', '-d', classes,
    join(PROJECT_ROOT, 'backend', 'docker', 'Healthcheck.java')]);
  assert.equal(compiled.code, 0, compiled.output);
  let status = 200;
  const server = createServer((_, response) => { response.statusCode = status; response.end(); });
  const port = await listen(server);
  try {
    const java = findExecutable('java');
    const args = ['-cp', classes, 'Healthcheck', `http://127.0.0.1:${port}/api/health/ready`];
    assert.equal((await capture(java, args)).code, 0);
    status = 503;
    assert.equal((await capture(java, args)).code, 1);
  } finally {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
});
