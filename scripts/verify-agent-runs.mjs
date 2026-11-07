// 在独立环境中验证异步面试命令、事件回放及浏览器提交行为。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile, writeFile, mkdir, open } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { liveStack } from './live-stack.mjs';
import { capture, findExecutable, assertPortFree, stopChild } from './dev.mjs';
const root = resolve(new URL('../', import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/, ''));
const nginx = process.env.NGINX_EXE || findExecutable('nginx');
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const checks = [], timings = {}, browserErrors = [];
const report = join(root, 'docs/evidence/agent-runs-live.json');
const sleep = ms => new Promise(r => setTimeout(r, ms));
// 心跳场景关闭工作器，让任务保持待执行状态，便于观察代理是否及时转发心跳。
for (const mode of ['normal', 'heartbeat']) {
  process.env.INTERVIEW_RUNS_WORKER_ENABLED = mode === 'normal' ? 'true' : 'false';
  const stack = await liveStack(false);
  let server, browser, logfile, nginxArgs, nginxPrefix;
  try {
    await assertPortFree(18081);
    const prefix = join(stack.dir, 'nginx');await mkdir(join(prefix, 'logs'), { recursive: true });await mkdir(join(prefix, 'temp'), { recursive: true });
    let serverConfig = await readFile(join(root, 'frontend/nginx.conf'), 'utf8');
    serverConfig = serverConfig.replace(/^\uFEFF/, "").replace('listen       80 default_server;', 'listen 127.0.0.1:18081;')
      .replace('/usr/share/nginx/html', join(root, 'frontend/dist').replaceAll('\\', '/'))
      .replaceAll('backend:8080', '127.0.0.1:18080');
    const config = join(prefix, 'nginx.conf');
    await writeFile(config, `daemon off; worker_processes 1; pid logs/nginx.pid; error_log logs/error.log; events { worker_connections 64; } http { include "${join(resolve(nginx, '..'), 'conf/mime.types').replaceAll('\\', '/')}"; access_log off; ${serverConfig} }`);
    const args = ['-p', prefix.replaceAll('\\', '/') + '/', '-c', config.replaceAll('\\', '/')];
    nginxArgs = args; nginxPrefix = prefix;
    const validation = await capture(nginx, [...args, '-t'], { cwd: prefix, timeout: 10000 });
    assert.equal(validation.code, 0, validation.output);
    logfile = await open(join(prefix, 'console.log'), 'w');
    server = spawn(nginx, args, { cwd: prefix, windowsHide: true, stdio: ['ignore', logfile.fd, logfile.fd] });
    for (let i = 0; i < 40; i++) { try { if ((await fetch('http://127.0.0.1:18081/healthz')).ok) break; } catch {} await sleep(100); }
    const origin = 'http://127.0.0.1:18081';
    const api = async (path, body, token, expected = body ? 200 : 200) => {
      const response = await fetch(origin + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) });
      const result = await response.json();assert.equal(response.status, expected, result.message);return result.data;
    };
    const username = 'runs_' + Date.now(), password = 'Acceptance123!';
    const owner = await api('/api/auth/register', { username, password, displayName: 'Run 验收' });
    const token = owner.token;
    const run = await api('/api/interviews/start-commands', { requestId: 'acceptance_' + Date.now(), configuration: { targetRole: 'Java 后端', totalRounds: 3, experienceMode: 'FORMAL' } }, token, 202);
    // 从给定事件序号续读；按空行拆分完整 SSE 帧，兼容网络分片。
    const stream = async (base, after, onFrame) => {
      const response = await fetch(base + `/api/interview-runs/${run.runId}/events?afterSeq=${after}`, { headers: { Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(25000) });
      assert.equal(response.status, 200);assert.match(response.headers.get('content-type'), /text\/event-stream/);
      const reader = response.body.getReader(), decoder = new TextDecoder();let buffer = '';
      try { for (;;) { const r = await reader.read();if (r.done) break;buffer += decoder.decode(r.value, { stream: true }).replaceAll('\r\n', '\n');let at;
        while ((at = buffer.indexOf('\n\n')) >= 0) { const frame = buffer.slice(0, at);buffer = buffer.slice(at + 2);if (await onFrame(frame)) return; }
      } } finally { await reader.cancel(); }
    };
    let last = 0;const started = Date.now();
    await stream(origin, 0, frame => {
      if (!timings[mode + 'FirstEventMs'] && frame.includes('data:')) timings[mode + 'FirstEventMs'] = Date.now() - started;
      const id = /(?:^|\n)id: ?(\d+)/.exec(frame);if (id) last = Number(id[1]);
      if (mode === 'heartbeat' && frame.startsWith(':')) { timings.heartbeatMs = Date.now() - started;return true; }
      if (mode === 'normal' && frame.includes('turn.committed')) return true;
    });
    assert.ok(timings[mode + 'FirstEventMs'] < 3000);
    if (mode === 'heartbeat') {
      assert.ok(timings.heartbeatMs >= 14000 && timings.heartbeatMs < 22000);
      await api(`/api/interview-runs/${run.runId}/cancel`, {}, token);
      let cancelled = false;
      await stream(origin, last, frame => { if (frame.includes('run.cancelled')) { cancelled = true;return true; } });
      assert.equal(cancelled, true);checks.push('Nginx delivers first event and 15-second heartbeat; reconnect replays cancellation after last sequence');
      continue;
    }
    let done = await api(`/api/interview-runs/${run.runId}`, null, token);assert.equal(done.status, 'SUCCEEDED');
    const session = await api(`/api/interviews/sessions/${done.sessionId}`, null, token);assert.equal(session.agent, null);
    let replayed = false;
    await stream('http://127.0.0.1:18080', 0, frame => { if (frame.includes('turn.committed')) { replayed = true;return true; } });assert.equal(replayed, true);
    checks.push('direct HTTP and production Nginx event paths replay the same committed session');
    browser = await chromium.launchPersistentContext(join(stack.dir, 'browser'), { channel: 'msedge', headless: true, viewport: { width: 1360, height: 980 }, env: { ...process.env, TMP: stack.tmp, TEMP: stack.tmp } });
    const page = await browser.newPage();page.setDefaultTimeout(15000);page.on('pageerror', e => browserErrors.push(e.message));page.on('response', r => {if(r.url().includes('/api/') && !r.ok()) console.log('HTTP',r.status(),new URL(r.url()).pathname);});
    await page.goto(origin + '/login');await page.getByLabel('用户名', { exact: true }).fill(username);await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();await page.waitForURL('**/interview');
    await page.goto(origin + '/interview?sessionId=' + done.sessionId);await page.getByLabel('面试回答').waitFor().catch(async error => {console.log('BROWSER',page.url(),(await page.locator('body').innerText()).slice(0,5000),browserErrors);throw error;});
    // 同时记录首次提交与刷新后的请求数，验证恢复页面不会重复发送回答命令。
    let posts = 0;page.on('request', r => { if (r.method() === 'POST' && r.url().includes('/answer-commands')) posts++; });
    await page.getByLabel('面试回答').fill('我使用条件更新确认原版本，冲突时重新读取，本次没有验证所有失败窗口。');
    await page.getByRole('button', { name: /提交回答|发送回答/ }).click();
    await page.waitForFunction(() => document.querySelector('textarea[aria-label="面试回答"]')?.value === '');
    assert.equal(posts, 1);assert.equal(await page.getByText(/即时反馈：/).count(), 0);assert.equal(await page.getByLabel('面试 Agent 考察计划').count(), 0);
    await page.reload();await page.getByLabel('面试回答').waitFor();assert.equal(posts, 1);
    await page.screenshot({ path: join(root, 'docs/evidence/agent-runs-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });await page.screenshot({ path: join(root, 'docs/evidence/agent-runs-mobile.png'), fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    checks.push('browser sends one command; draft clears after committed receipt; formal mode and reload remain safe at desktop/mobile widths');
    assert.deepEqual(browserErrors, []);
  } finally {
    await browser?.close();
    if (server) { await capture(nginx, [...nginxArgs, '-s', 'quit'], { cwd: nginxPrefix, timeout: 10000 }).catch(() => {}); await stopChild(server); if (server.exitCode === null) server.kill(); }
    await logfile?.close();await stack.cleanup();
  }
}
await writeFile(report, JSON.stringify({ provider: 'local Mock; real MySQL, HTTP, browser and Nginx', checks, timings, browserErrors }, null, 2) + '\n');
console.log(JSON.stringify({ checks, timings, browserErrors }, null, 2));
