import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { liveStack } from './live-stack.mjs';

// 使用真实页面、HTTP 和一次性数据库，不调用外部模型。
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const stack = await liveStack(false);
let browser;
const checks = [];
const evidence = name => new URL('../docs/evidence/' + name, import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/, '');
try {
  const api = async (path, body, token, method = body ? 'POST' : 'GET', expected = 200) => {
    const response = await fetch(stack.origin + '/api' + path, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30000),
    });
    const result = await response.json(); assert.equal(response.status, expected, JSON.stringify(result)); return result.data;
  };
  const password = 'Acceptance123!';
  const username = 'history_' + Date.now();
  const auth = await api('/auth/register', { username, password, displayName: '历史验收' });
  const otherName = 'other_' + Date.now();
  const other = await api('/auth/register', { username: otherName, password, displayName: '隔离验收' });
  browser = await chromium.launchPersistentContext(stack.dir + '/browser', {
    channel: 'msedge', headless: true, reducedMotion: 'reduce', env: { ...process.env, TMP: stack.tmp, TEMP: stack.tmp }, viewport: { width: 1360, height: 1000 },
  });
  const page = await browser.newPage(); page.setDefaultTimeout(15000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  let modelRequests = 0;
  page.on('request', request => { if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/knowledge/answers') modelRequests++; });
  const login = async name => {
    await page.goto(stack.origin + '/login');
    await page.getByLabel('用户名', { exact: true }).fill(name);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL('**/interview');
  };
  await login(username);
  await page.goto(stack.origin + '/knowledge');
  await page.getByRole('button', { name: /粘贴笔记/ }).click();
  await page.getByLabel('资料标题', { exact: true }).fill('缓存笔记');
  await page.getByLabel('笔记正文', { exact: true }).fill('# 缓存笔记\n\n缓存 TTL 90 秒，旧标记 amber。🙂\n');
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
  await page.getByText('原文 · v1', { exact: true }).waitFor();
  const documentId = new URL(page.url()).searchParams.get('document'); assert.ok(documentId);
  await page.getByRole('textbox', { name: '资料问题', exact: true }).fill('缓存 TTL 是多少？');
  await page.getByRole('button', { name: '检索并回答', exact: true }).click();
  const savedLink = page.getByRole('link', { name: '已保存 · 查看这次问答', exact: true });
  await savedLink.waitFor(); const detailHref = await savedLink.getAttribute('href');
  const answerId = new URL(detailHref, stack.origin).searchParams.get('answer'); assert.ok(answerId);
  await page.reload();
  await page.getByRole('link', { name: '问答历史', exact: true }).click();
  await page.getByRole('complementary', { name: '问答历史列表' }).getByRole('link').first().click();
  const detail = page.getByRole('region', { name: '已保存问答详情' });
  await detail.getByRole('heading', { name: '缓存 TTL 是多少？', exact: true }).waitFor();
  const stored = await api('/knowledge/answers/' + answerId, null, auth.token);
  assert.ok(Math.abs(Date.now() - Date.parse(stored.createdAt)) < 60000, 'Saved time must match actual request time');
  await page.reload();
  await detail.getByRole('link', { name: /缓存笔记 · v1/ }).waitFor();
  assert.equal(modelRequests, 1);
  checks.push('选择资料提问 → 刷新 → 从历史列表打开 → 再刷新详情，均读取同一已保存回答，只有一次 POST 问答请求');
  console.log(checks.at(-1));

  await page.goto(stack.origin + '/knowledge?document=' + documentId);
  await page.getByRole('button', { name: '编辑正文', exact: true }).click();
  await page.getByLabel('编辑正文', { exact: true }).fill('# 缓存笔记\n\n缓存 TTL 180 秒，新标记 cobalt。🙂\n');
  await page.getByRole('button', { name: '保存为新版本', exact: true }).click();
  await page.getByText('原文 · v2', { exact: true }).waitFor();
  await page.goto(stack.origin + detailHref);
  const citation = detail.getByRole('link', { name: /缓存笔记 · v1/ }); await citation.waitFor();
  await page.screenshot({ path: evidence('knowledge-history-desktop.png') });
  await citation.click();
  await page.getByText('原文 · v1', { exact: true }).waitFor();
  const chunk = new URL(page.url()).searchParams.get('chunk');
  assert.match(await page.locator('[id="source-' + chunk + '"]').innerText(), /90 秒.*amber/);
  assert.deepEqual((await api('/knowledge/answers/' + answerId, null, auth.token)).answer, stored.answer);
  checks.push('编辑原资料为 v2 → 历史回答不变 → 旧引用定位 v1 的 90 秒原文与原片段');
  console.log(checks.at(-1));

  await page.getByRole('button', { name: /退出登录/ }).click();
  await page.waitForURL('**/login'); await login(otherName);
  await page.goto(stack.origin + detailHref);
  await detail.getByText('问答记录不存在或已删除', { exact: true }).waitFor();
  assert.equal(await detail.getByText(/90 秒/).count(), 0);
  await api('/knowledge/answers/' + answerId, null, other.token, 'GET', 404);
  await api('/knowledge/answers/' + answerId, null, other.token, 'DELETE', 404);
  checks.push('退出并切换到另一账号 → 同一历史地址不可读取，列表为空，越权 GET/DELETE 返回 404');
  console.log(checks.at(-1));
  await page.getByRole('button', { name: /退出登录/ }).click();
  await page.waitForURL('**/login'); await login(username);
  await page.goto(stack.origin + detailHref);
  await detail.getByRole('link', { name: /缓存笔记 · v1/ }).waitFor();
  await api('/knowledge/documents/' + documentId, null, auth.token, 'DELETE');
  await page.getByRole('button', { name: '刷新历史', exact: true }).click();
  await detail.getByText('来源不可用（资料已删除）', { exact: true }).first().waitFor();
  assert.equal(await detail.getByRole('link').count(), 0);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await detail.getByText('来源不可用（资料已删除）', { exact: true }).first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: evidence('knowledge-history-mobile.png') });
  await page.getByRole('button', { name: '删除问答记录', exact: true }).click();
  await page.getByRole('button', { name: '确认删除记录', exact: true }).click();
  await page.getByText('还没有问答记录', { exact: true }).waitFor();
  await api('/knowledge/answers/' + answerId, null, auth.token, 'GET', 404);
  await page.goto(stack.origin + detailHref);
  await detail.getByText('问答记录不存在或已删除', { exact: true }).waitFor();
  checks.push('删除原资料 → 历史摘录保留且来源不可用 → 删除本人记录 → 旧地址及 API 均不可再读取；390 px 无横向溢出');
  console.log(checks.at(-1));

  const extra = await api('/knowledge/documents', { title: '分页样本', content: '缓存 TTL 90 秒。' }, auth.token);
  const ids = [];
  for (let n = 0; n < 21; n++) ids.push((await api('/knowledge/answers', { question: '火星半径 ' + n, documentIds: [extra.id] }, auth.token)).historyId);
  await page.goto(stack.origin + '/knowledge/history');
  const list = page.getByRole('complementary', { name: '问答历史列表' });
  await list.getByRole('link').first().waitFor(); assert.equal(await list.getByRole('link').count(), 20);
  await list.locator('.ant-pagination-next button').click();
  await page.waitForURL('**/knowledge/history?page=2');
  await list.getByRole('link').first().waitFor(); assert.equal(await list.getByRole('link').count(), 1);
  await list.getByRole('link').first().click();
  await detail.getByText('所选资料不足以回答这个问题。请补充资料或换一种更具体的问法。', { exact: true }).waitFor();
  const latestAuth = await api('/auth/login', { username, password });
  await api('/users/me', { currentPassword: password }, latestAuth.token, 'DELETE');
  await api('/knowledge/answers/' + ids[0], null, latestAuth.token, 'GET', 401);
  checks.push('资料不足结果保存并可打开；21 条记录分页为 20+1；注销账号后令牌失效（实库测试另核对级联物理清理）');
  assert.equal(modelRequests, 1); assert.deepEqual(errors, []);
  await writeFile(evidence('knowledge-history-live.json'), JSON.stringify({ stack: 'Real React/Vite → Spring Boot HTTP → disposable MySQL; local Mock AI', checks, answerId, documentId, questionPostRequestsFromBrowser: modelRequests, browserErrors: errors }, null, 2));
} catch (error) {
  console.error((await readFile(stack.dir + '/backend.log', 'utf8')).split('\n').slice(-12).join('\n'));
  throw error;
} finally { await browser?.close(); await stack.cleanup(); }
