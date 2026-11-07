// 真实 HTTP + MySQL + Redis 的短流程；只有模型使用 Mock，不伪造业务 API。
import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startBrowserStack } from '../../scripts/browser-stack.mjs';
import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--ci-services')) throw new Error('Usage: verify-demo-browser.mjs [--ci-services]');
const output = join(root, 'outputs', 'browser-e2e');
await mkdir(output, { recursive: true });
const reportDirectory = await mkdtemp(join(output, 'run-'));
const controller = new AbortController();
const interrupt = () => controller.abort();
process.once('SIGINT', interrupt);
process.once('SIGTERM', interrupt);
const deadline = setTimeout(interrupt, 270000);
let stack, browser, page, failure;
const checks = [], errors = [], commands = [], streamStatuses = [];
const report = { schemaVersion: 'browser-demo-v1', status: 'FAILED', model: 'mock', realProviderCalls: 0,
  aiQualityAccepted: false, checks, errors };
// Closing the browser interrupts any in-flight locator immediately on timeout/cancellation.
const abortBrowser = () => { void browser?.close().catch(() => {}); };
controller.signal.addEventListener('abort', abortBrowser);
const checkpoint = text => { checks.push(text); console.log('[browser-e2e] ' + text); };
try {
  stack = await startBrowserStack({ ciServices: args.includes('--ci-services'), signal: controller.signal });
  Object.assign(process.env, { TMP: stack.tmp, TEMP: stack.tmp, TMPDIR: stack.tmp });
  const chromium = await loadChromium();
  controller.signal.throwIfAborted();
  browser = await chromium.launchPersistentContext(join(stack.dir, 'browser'), {
    ...browserLaunchOptions(), headless: true, viewport: { width: 1360, height: 1000 },
    env: { ...stack.environment, TMP: stack.tmp, TEMP: stack.tmp, TMPDIR: stack.tmp },
  });
  browser.setDefaultTimeout(20000);
  await browser.route('**/*', route => new URL(route.request().url()).origin === stack.origin
    ? route.continue() : route.abort());
  page = await browser.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    if (request.method() === 'POST' && /\/(start-commands|answer-commands)$/.test(new URL(request.url()).pathname))
      commands.push(request.url());
  });
  page.on('response', response => {
    if (/\/api\/interview-runs\/[^/]+\/events$/.test(new URL(response.url()).pathname))
      streamStatuses.push(response.status());
  });
  await page.goto(stack.origin + '/login');
  await page.getByRole('button', { name: '进入独立演示空间', exact: true }).click();
  await page.waitForURL('**/interview');
  checkpoint('通过浏览器创建独立演示空间');
  const token = await page.evaluate(() => localStorage.getItem('token'));
  assert.ok(token, 'Demo login must persist its real server token');
  const getSession = async id => {
    controller.signal.throwIfAborted();
    const response = await fetch(`${stack.origin}/api/interviews/sessions/${id}`, {
      headers: { Authorization: `Bearer ${token}` }, redirect: 'error',
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.success, true);
    return body.data;
  };
  await page.getByText('本场自定义 JD', { exact: true }).click();
  await page.getByLabel('面试岗位名称', { exact: true }).fill('Java 后端工程师');
  await page.getByLabel('自定义岗位 JD', { exact: true }).fill('负责订单接口与库存服务，熟悉 Java、MySQL 事务、乐观锁和接口幂等。请结合测试说明故障恢复方案。');
  await page.locator('#interview-rounds').press('ArrowDown');
  await page.locator('.ant-select-item-option-content').getByText('3 轮', { exact: true }).click();
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.getByLabel('面试回答', { exact: true }).waitFor();
  const id = new URL(page.url()).searchParams.get('sessionId');
  assert.ok(id);
  const answer = '我负责订单接口的幂等与库存更新。请求号有唯一约束，库存通过版本号条件更新，冲突时重新读取。数据库提交失败时返回错误，结果未知时先查询请求记录。我测试了重复提交和并发扣减，但尚未验证全部网络故障窗口。';
  for (let round = 1; round <= 3; round++) {
    await page.getByLabel('面试回答', { exact: true }).fill(answer);
    await page.getByRole('button', { name: /提交回答/ }).click();
    const until = Date.now() + 30000;
    let saved;
    while (Date.now() < until) {
      saved = await getSession(id);
      if (saved.turns.filter(turn => turn.speaker === 'user').length === round) break;
      await new Promise(done => setTimeout(done, 200));
    }
    assert.equal(saved.turns.filter(turn => turn.speaker === 'user').length, round, 'Answer must be committed by the backend');
    if (round < 3) await page.waitForFunction(() => document.querySelector('textarea[aria-label="面试回答"]')?.value === '');
    checkpoint(`第 ${round} 轮回答已由后端持久化`);
    if (round === 1) {
      const submitted = commands.length;
      await page.reload();
      await page.getByLabel('面试回答', { exact: true }).waitFor();
      assert.equal(commands.length, submitted, 'Reload must not resubmit an answer');
      checkpoint('刷新后恢复当前面试且未重发回答');
    }
  }
  await page.getByText('面试结束', { exact: true }).waitFor();
  await page.getByRole('button', { name: '查看能力复盘', exact: true }).click();
  const review = page.getByRole('region', { name: '面试能力复盘', exact: true });
  await review.getByRole('heading', { name: '回答证据与练习建议', exact: true }).waitFor();
  await review.getByText('当前为规则演示，评估用于体验流程，不代表真实模型判断。', { exact: true }).waitFor();
  const finished = await getSession(id);
  assert.equal(finished.state, 'FINISHED');
  assert.equal(finished.agent.executionMode, 'mock');
  assert.equal(finished.agent.evaluations.length, 3);
  assert.equal(commands.length, 4, 'Exactly one start command and three answers');
  checkpoint('完成三轮面试并显示明确标注 Mock 的能力复盘');
  await page.reload();
  await review.getByRole('heading', { name: '回答证据与练习建议', exact: true }).waitFor();
  assert.equal((await getSession(id)).state, 'FINISHED');
  assert.equal(commands.length, 4);
  assert.ok(streamStatuses.includes(200), 'At least one real SSE connection must succeed');
  assert.ok(streamStatuses.every(status => status === 200), `Unexpected SSE response: ${streamStatuses}`);
  assert.deepEqual(errors, []);
  checkpoint('复盘刷新后仍可读取，真实 SSE 连接成功，无页面运行时异常');
  await page.screenshot({ path: join(reportDirectory, 'review.png'), fullPage: true });
  Object.assign(report, { status: 'MOCK_DEMO_FLOW_PASSED', commands: commands.length, streamStatuses });
} catch (error) {
  failure = error;
  report.failure = String(error);
  if (error.cleanupError) report.cleanupFailure = String(error.cleanupError);
  for (const [name, contents] of Object.entries(error.browserStackLogs || {})) {
    if (contents) await writeFile(join(reportDirectory, name.replaceAll('/', '-')), contents);
  }
  if (page && !page.isClosed()) {
    await page.screenshot({ path: join(reportDirectory, 'failure.png'), fullPage: true, timeout: 5000 }).catch(() => {});
    await writeFile(join(reportDirectory, 'visible.txt'), await page.locator('body').innerText({ timeout: 3000 }).catch(() => ''));
  }
} finally {
  clearTimeout(deadline);
  controller.signal.removeEventListener('abort', abortBrowser);
  await browser?.close().catch(() => {});
  if (stack) {
    // Only synthetic run diagnostics are retained; never write tokens or process environment.
    if (failure) for (const name of await readdir(stack.dir).catch(() => [])) {
      if (['backend.log', 'frontend.log', 'mysql.log', 'redis.log'].includes(name))
        await copyFile(join(stack.dir, name), join(reportDirectory, name)).catch(() => {});
    }
    try { await stack.cleanup(); }
    catch (error) { failure ||= error; report.status = 'FAILED'; report.cleanupFailure = String(error); }
  }
  process.removeListener('SIGINT', interrupt);
  process.removeListener('SIGTERM', interrupt);
  await writeFile(join(reportDirectory, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  console.log('[browser-e2e] Report: ' + join(reportDirectory, 'report.json'));
}
if (failure) { console.error(failure.message); process.exitCode = 1; }
