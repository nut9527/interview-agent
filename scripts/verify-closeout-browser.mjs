// v1.0 主流程浏览器检查；独立构建、空库与 Mock，不读取 .env 或调用供应商。
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, realpath, rm } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { liveStack } from './live-stack.mjs';
import { evaluationEnv } from './evaluate-closeout.mjs';
import { capture, findExecutable } from './dev.mjs';
import { projectRoot } from './release-candidate.mjs';

import { loadChromium, browserLaunchOptions } from '../frontend/scripts/browser-runtime.mjs';
const chromium = await loadChromium();
if (process.argv.length > 2) throw new Error('Usage: verify-closeout-browser.mjs');
const parent = join(projectRoot, '.runtime'); await mkdir(parent, { recursive: true });
const build = await mkdtemp(join(parent, 'closeout-browser-build-'));
const environment = { ...evaluationEnv(process.env, 'http://127.0.0.1:1', '', ''),
  AI_PROVIDER: 'mock', DEEPSEEK_API_KEY: '', AI_API_KEY: '', INTERVIEW_RUNS_WORKER_ENABLED: 'true',
  VITE_API_BASE_URL: '', MAVEN_ARGS: '', TMP: build, TEMP: build };
let stack, browser, page;
const checks = [], errors = [], commands = [];
const password = randomUUID(), username = 'closeout_' + randomUUID().replaceAll('-', '').slice(0, 12);
let token;
const api = async (path, method = 'GET', body) => {
  const response = await fetch(stack.origin + path, { method, redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  assert.ok(response.ok && result.success, `${method} ${path}: ${response.status} ${result.message}`);
  return result.data;
};
const waitUntil = async condition => {
  for (let n = 0; n < 100; n++) { if (await condition()) return; await new Promise(done => setTimeout(done, 100)); }
  throw new Error('Expected persisted state did not arrive');
};
const sessionId = () => new URL(page.url()).searchParams.get('sessionId');
try {
  console.log('[closeout-browser] Building current backend in an owned directory.');
  const built = await capture(findExecutable('mvn'), ['-o', '-B', '-ntp', '-DskipTests',
    `-Dzhizhida.build.directory=${build}`, 'package'], { cwd: join(projectRoot, 'backend'), env: environment, timeout: 300000 });
  assert.equal(built.code, 0, built.output.slice(-3500));
  process.env.LIVE_BACKEND_JAR = join(build, 'zhizhida-backend-0.1.0-SNAPSHOT.jar');
  stack = await liveStack(false, { environment });
  token = (await api('/api/auth/register', 'POST', { username, password, displayName: '收尾验收临时账号' })).token;
  browser = await chromium.launchPersistentContext(join(stack.dir, 'browser'), { ...browserLaunchOptions(), headless: true,
    env: { ...environment, TMP: stack.tmp, TEMP: stack.tmp }, viewport: { width: 1360, height: 1000 },
    args: ['--disable-gpu', '--disable-gpu-shader-disk-cache'] });
  browser.setDefaultTimeout(15000);
  // 禁止测试页面访问本机服务以外的地址。
  await browser.route('**/*', route => new URL(route.request().url()).origin === stack.origin ? route.continue() : route.abort());
  page = await browser.newPage(); page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (r.method() === 'POST' && /\/(answer-commands|start-commands)$/.test(new URL(r.url()).pathname)) commands.push(r.postDataJSON()); });
  await page.goto(stack.origin + '/login');
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL('**/interview');
  await page.getByText('本场自定义 JD', { exact: true }).click();
  await page.getByLabel('面试岗位名称', { exact: true }).fill('Java 后端实习生');
  await page.getByLabel('自定义岗位 JD', { exact: true }).fill('负责 Java 后端接口，使用 MySQL 与 Redis，说明项目职责、并发写入与故障恢复；用可核对的测试描述验证结果。');
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.getByLabel('面试回答', { exact: true }).waitFor();
  const original = sessionId(); assert.ok(original);
  const answer = '我负责接口的归属检查，按本人 ID 查询后再更新。并发时按原版本条件更新，冲突后重新读取；未知写入结果先核对请求号。我做过本人请求成功和跨账号拒绝测试，但没有完整验证网络中断后的重试窗口。';
  const answerRounds = async (sid, rounds) => {
    for (let i = 0; i < rounds; i++) {
      await page.getByLabel('面试回答', { exact: true }).fill(answer);
      await page.getByRole('button', { name: /提交回答/ }).click();
      await waitUntil(async () => (await api(`/api/interviews/sessions/${sid}`)).turns.filter(t => t.speaker === 'user').length === i + 1);
      if (i + 1 < rounds) await page.waitForFunction(() => document.querySelector('textarea[aria-label="面试回答"]')?.value === '');
      console.log(`[closeout-browser] ${rounds}-round flow: answer ${i + 1} persisted.`);
      if (i === 0) {
        const count = commands.length;
        await page.reload(); await page.getByLabel('面试回答', { exact: true }).waitFor();
        assert.equal(commands.length, count, 'Reload must not submit another command');
      }
    }
    await page.getByText('面试结束', { exact: true }).waitFor();
    const finished = await api(`/api/interviews/sessions/${sid}`);
    assert.equal(finished.state, 'FINISHED'); assert.equal(finished.agent.evaluations.length, rounds);
    return finished;
  };
  await answerRounds(original, 6); checks.push('浏览器 JD 开场、6 轮回答、刷新不重发、服务端完成复盘');
  await page.getByText('查看本场运行过程', { exact: true }).click();
  await page.getByText('本次采用基础流程，未记录逐步观察', { exact: false }).first().waitFor();
  const runs = await api(`/api/interviews/sessions/${original}/runs`);
  assert.equal(runs.length, 7); assert.ok(runs.every(run => run.status === 'SUCCEEDED'));
  await page.getByText('停止原因：本轮处理完成', { exact: true }).first().waitFor();
  checks.push('B0 运行终态与停止原因可展开核对，开场及六次回答均已持久成功');
  await page.getByRole('button', { name: '复练此能力 · 3 轮', exact: true }).first().click();
  await page.getByLabel('面试回答', { exact: true }).waitFor();
  const retry = sessionId(); assert.ok(retry && retry !== original);
  const retried = await answerRounds(retry, 3);
  assert.equal(retried.agent.training.retry.sourceSessionId, original);
  await page.getByLabel('复练前后对照', { exact: true }).waitFor();
  checks.push('从复盘发起关联 3 轮复练，页面与服务端保存的来源和结果一致');
  await page.setViewportSize({ width: 390, height: 844 });
  await waitUntil(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
  checks.push('390px 复盘页面无整页横向溢出');
  await page.setViewportSize({ width: 1360, height: 1000 });
  await page.goto(stack.origin + '/knowledge');
  await page.getByRole('button', { name: /粘贴笔记/ }).click();
  await page.getByLabel('资料标题', { exact: true }).fill('收尾验收合成笔记');
  await page.getByLabel('笔记正文', { exact: true }).fill('# 归属检查\n读取与更新都按本人 ID 限定范围。\n# 并发更新\n使用版本条件更新；冲突时重新读取，未知结果先核对请求号。');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await page.getByLabel('选择资料：收尾验收合成笔记', { exact: true }).check();
  await page.getByRole('button', { name: '从所选资料生成题目', exact: true }).click();
  await page.getByLabel('生成题数', { exact: true }).fill('1');
  await page.getByRole('button', { name: '生成候选题', exact: true }).click();
  await page.getByLabel('自测题干', { exact: true }).waitFor();
  const stem = await page.getByLabel('自测题干', { exact: true }).inputValue();
  assert.ok(stem.trim());
  await page.getByLabel('已逐题核对题干、考察点、参考要点与来源', { exact: true }).check();
  await page.getByRole('button', { name: '确认并保存到我的题目', exact: true }).click();
  await page.getByText('本次候选题已处理完成，可到「我的题目」查看。', { exact: true }).waitFor();
  await page.getByRole('button', { name: '查看我的题目', exact: true }).click();
  await page.getByRole('heading', { name: stem, exact: true }).waitFor();
  await page.reload(); await page.getByRole('heading', { name: stem, exact: true }).waitFor();
  assert.equal((await api('/api/knowledge/self-tests?confirmed=true&page=1&size=20')).items[0].question.stem, stem);
  checks.push('短笔记导入、Mock 候选题生成、逐题确认保存与刷新读取');
  const fault = await api('/api/interviews/start-commands', 'POST', { requestId: randomUUID(),
    configuration: { targetRole: 'Java 后端', totalRounds: 3, experienceMode: 'TEACHING' } });
  await waitUntil(async () => (await api(`/api/interview-runs/${fault.runId}`)).status === 'SUCCEEDED');
  const faultRun = await api(`/api/interview-runs/${fault.runId}`);
  await page.goto(stack.origin + `/interview?sessionId=${faultRun.sessionId}`);
  const failedAnswer = '故障验证回答：发送失败时，这段原文必须保留。';
  await page.getByLabel('面试回答', { exact: true }).fill(failedAnswer);
  const faultPath = `**/api/interviews/sessions/${faultRun.sessionId}/answer-commands`;
  await page.route(faultPath, route => route.fulfill({ status: 503,
    json: { success: false, code: 'SERVICE_UNAVAILABLE', message: '收尾验收注入的临时发送失败' } }));
  await page.getByRole('button', { name: /提交回答/ }).click();
  // 暂时失败会进入按 requestId 核对流程；最终错误可能来自该核对请求。
  await page.getByRole('button', { name: '核对并恢复', exact: true }).waitFor();
  assert.equal(await page.getByLabel('面试回答', { exact: true }).inputValue(), failedAnswer);
  assert.equal((await api(`/api/interviews/sessions/${faultRun.sessionId}`)).turns.filter(t => t.speaker === 'user').length, 0);
  await page.unroute(faultPath);
  checks.push('注入 HTTP 503 后页面保留回答，数据库未新增回答；此项为浏览器故障注入');
  await page.getByRole('button', { name: '账号菜单', exact: true }).click();
  await page.getByText('退出登录', { exact: true }).click(); await page.waitForURL('**/login');
  const otherName = 'closeout_' + randomUUID().replaceAll('-', '').slice(0, 12);
  const other = await api('/api/auth/register', 'POST', { username: otherName, password, displayName: '收尾隔离账号' });
  await page.getByLabel('用户名', { exact: true }).fill(otherName);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL('**/interview');
  assert.ok(!(await page.locator('body').innerText()).includes(failedAnswer));
  await page.goto(stack.origin + '/knowledge/practice');
  await page.getByText('还没有已确认题目，请先到「资料与出题」生成并核对', { exact: true }).waitFor();
  assert.equal(await page.getByRole('heading', { name: stem, exact: true }).count(), 0);
  const ownerToken = token; token = other.token;
  try {
    assert.equal((await api('/api/interviews/sessions?page=1&size=20')).total, 0);
    const denied = await fetch(stack.origin + `/api/interviews/sessions/${original}`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(5000) });
    assert.equal(denied.status, 404);
    await api('/api/users/me', 'DELETE', { currentPassword: password });
  } finally { token = ownerToken; }
  checks.push('同一浏览器切换账号后无旧回答或个人题目，跨账号旧会话返回 404');
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ checks, commands: commands.length, realProviderCalls: 0,
    status: 'MOCK_MAIN_FLOW_PASSED',
    note: '运行中迟到回调由 InterviewModelToolsIT.ToolLoop 独立验证；Mock 不替代真实供应商验收。' }));
} catch (error) {
  if (page) console.error('[closeout-browser] Visible state:', (await page.locator('body').innerText().catch(() => '')).slice(-5000));
  throw error;
} finally {
  await browser?.close();
  if (token && stack) await api('/api/users/me', 'DELETE', { currentPassword: password }).catch(() => console.error('Temporary account cleanup failed; disposable database cleanup follows.'));
  await stack?.cleanup();
  const actual = await realpath(build);
  assert.ok(actual.startsWith(await realpath(parent) + sep + 'closeout-browser-build-'));
  await rm(actual, { recursive: true, maxRetries: 5, retryDelay: 300 });
}
