import { startInterview, answerInterview } from './interview-run-client.mjs';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { liveStack } from './live-stack.mjs';

// 使用真实应用、隔离 MySQL 和本地 Mock AI。
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const stack = await liveStack(false);
let browser;
const checks = [];
const original = '# 缓存笔记\r\n\r\n缓存 TTL 90 秒，旧标记 amber。🙂\r\n';
const updated = '# 缓存笔记\n\n缓存 TTL 180 秒，新标记 cobalt。🙂\n';
const merged = updated + '\n本地补充：合并后需要测量命中率。\n';
const evidence = name => new URL('../docs/evidence/' + name, import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/, '');
try {
  const api = async (path, body, token, method = body ? 'POST' : 'GET') => {
    const response = await fetch(stack.origin + '/api' + path, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30000),
    });
    const result = await response.json(); assert.equal(response.status, 200, JSON.stringify(result)); return result.data;
  };
  const auth = await api('/auth/register', { username: 'versions_' + Date.now(), password: 'Acceptance123!', displayName: '版本验收' });
  browser = await chromium.launchPersistentContext(stack.dir + '/browser', {
    channel: 'msedge', headless: true, reducedMotion: 'reduce', env: { ...process.env, TMP: stack.tmp, TEMP: stack.tmp }, viewport: { width: 1360, height: 1000 },
  });
  const page = await browser.newPage(); page.setDefaultTimeout(15000);
  await page.goto(stack.origin + '/login');
  await page.evaluate(a => { localStorage.setItem('token', a.token); localStorage.setItem('user', JSON.stringify(a.user)); }, auth);
  await page.goto(stack.origin + '/knowledge');
  const parsed = page.waitForResponse(r => r.url().endsWith('/api/knowledge/imports/jobs'));
  await page.getByLabel('上传资料文件').setInputFiles({ name: '版本笔记.md', mimeType: 'text/markdown', buffer: Buffer.from(original) });
  await parsed;
  await page.getByRole('checkbox', { name: '我已核对文序、文字与完整性，确认以上正文入库' }).check();
  const upload = page.waitForResponse(r => r.url().includes('/api/knowledge/imports/jobs/') && r.url().endsWith('/confirm'));
  await page.getByRole('button', { name: '确认入库为 v1', exact: true }).click();
  const doc = (await (await upload).json()).data;
  await page.getByRole('heading', { name: doc.title, exact: true }).waitFor();
  await page.getByRole('textbox', { name: '资料问题', exact: true }).fill('缓存 TTL 是多少？');
  await page.getByRole('button', { name: '检索并回答', exact: true }).click();
  const panel = page.getByRole('region', { name: '基于资料问答' });
  const citation = panel.locator('blockquote a').first(); await citation.waitFor();
  const citationHref = await citation.getAttribute('href'); assert.match(citationHref, /version=1/);
  const session = await startInterview(stack.origin, auth.token, { targetRole: '版本训练', trainingTopic: 'DATABASE', totalRounds: 3, knowledgeDocumentIds: [doc.id] });
  assert.equal(session.agent.training.knowledge[0].content, original);
  assert.equal(session.agent.training.knowledge[0].version, 1);

  const stale = await browser.newPage(); stale.setDefaultTimeout(15000);
  await stale.goto(stack.origin + '/knowledge?document=' + doc.id);
  await stale.getByRole('button', { name: '编辑正文', exact: true }).click();
  await stale.getByLabel('编辑正文', { exact: true }).fill(merged);
  await page.getByRole('button', { name: '编辑正文', exact: true }).click();
  await page.getByLabel('编辑正文', { exact: true }).fill(updated);
  await page.getByRole('button', { name: '保存为新版本', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByText('原文 · v2', { exact: true }).waitFor();
  assert.equal((await api('/knowledge/documents/' + doc.id, null, auth.token)).id, doc.id);
  await page.getByLabel('搜索资料', { exact: true }).fill('cobalt');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.getByRole('region', { name: '搜索结果' }).getByText(/TTL 180 秒/).waitFor();
  assert.equal((await api('/knowledge/search?query=amber', null, auth.token)).length, 0);
  assert.equal((await api('/knowledge/search?query=cobalt', null, auth.token))[0].version, 2);
  await page.screenshot({ path: evidence('knowledge-v2.png') });
  // 保存后原页面回答仍可读，并链接到归档正文。
  assert.equal(await citation.getAttribute('href'), citationHref);
  await citation.click();
  await page.getByText('原文 · v1', { exact: true }).waitFor();
  const chunk = new URL(page.url()).searchParams.get('chunk'); assert.ok(chunk);
  assert.match(await page.locator('[id="source-' + chunk + '"]').innerText(), /90 秒/);
  await page.reload();
  await page.getByText('原文 · v1', { exact: true }).waitFor();
  assert.match(await page.locator('[id="source-' + chunk + '"]').innerText(), /amber/);
  const continued = await answerInterview(stack.origin, auth.token, session.sessionId, { answer: '我会核对 90 秒 TTL，测量命中率。', expectedTurn: 1, requestId: 'versions-first' });
  assert.deepEqual(continued.agent.training.knowledge, session.agent.training.knowledge);
  const reopened = await api('/interviews/sessions/' + session.sessionId, null, auth.token);
  assert.deepEqual(reopened.agent.training.knowledge, session.agent.training.knowledge);
  checks.push('上传 v1 → 问答和训练 → 页面编辑保存 v2 → 当前搜索命中新内容 → 旧引用（含刷新定位）与继续训练仍核对 v1');
  console.log(checks.at(-1));

  await page.getByRole('button', { name: '查看当前版本', exact: true }).click();
  await page.getByText('原文 · v2', { exact: true }).waitFor();
  await page.getByRole('button', { name: '版本历史', exact: true }).click();
  await page.getByRole('button', { name: /^v1 ·/ }).click();
  await page.getByRole('region', { name: '历史正文预览' }).getByText(/amber/).waitFor();
  await page.getByRole('button', { name: '恢复此正文为 v3', exact: true }).waitFor();
  // 第二个窗口的过期编辑必须保留草稿，并要求显式处理冲突。
  await stale.getByRole('button', { name: '保存为新版本', exact: true }).click();
  await stale.getByText('其他窗口已更新正文，本地编辑已保留。请核对后合并内容。', { exact: true }).waitFor();
  assert.equal(await stale.getByLabel('编辑正文', { exact: true }).inputValue(), merged);
  await stale.getByRole('region', { name: '服务器最新正文' }).getByText(/cobalt/).waitFor();
  assert.ok(await stale.getByRole('button', { name: '保存为新版本', exact: true }).isDisabled());
  await stale.screenshot({ path: evidence('knowledge-conflict.png') });
  await stale.getByRole('button', { name: '已核对，以 v2 为基准继续编辑', exact: true }).click();
  await stale.getByRole('button', { name: '保存为新版本', exact: true }).click();
  await stale.getByRole('dialog').waitFor({ state: 'hidden' });
  await stale.getByText('原文 · v3', { exact: true }).waitFor();
  // 在 v2 打开的历史页面也应识别恢复冲突。
  await page.getByRole('button', { name: '恢复此正文为 v3', exact: true }).click();
  await page.getByText('其他窗口已更新正文。本次未恢复，请刷新历史并核对最新版后重新选择。', { exact: true }).waitFor();
  assert.equal((await api('/knowledge/documents/' + doc.id, null, auth.token)).version, 3);
  await page.getByRole('button', { name: '刷新历史', exact: true }).click();
  await page.getByRole('button', { name: /^v3 ·/ }).waitFor();
  await page.getByRole('button', { name: /^v1 ·/ }).click();
  await page.getByRole('button', { name: '恢复此正文为 v4', exact: true }).click();
  await page.getByText('原文 · v4', { exact: true }).waitFor();
  assert.equal((await api('/knowledge/documents/' + doc.id, null, auth.token)).content, original);
  assert.equal((await api('/knowledge/documents/' + doc.id + '/versions/2', null, auth.token)).content, updated);
  assert.equal((await api('/knowledge/documents/' + doc.id + '/versions/3', null, auth.token)).content, merged);
  assert.equal((await api('/knowledge/documents/' + doc.id + '/versions', null, auth.token)).total, 4);
  checks.push('双窗口保存冲突保留草稿、并排核对与显式合并；历史恢复也检查冲突，恢复 v1 生成 v4 并完整保留 v1～v3');
  console.log(checks.at(-1));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '版本历史', exact: true }).click();
  await page.getByRole('button', { name: /^v2 ·/ }).click();
  await page.getByRole('region', { name: '历史正文预览' }).getByText(/180 秒/).waitFor();
  const selection = await page.getByRole('button', { name: /^v2 ·/, pressed: true }).evaluate(element => {
    const style = getComputedStyle(element); return { color: style.color, background: style.backgroundColor };
  });
  assert.notEqual(selection.color, selection.background);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await page.screenshot({ path: evidence('knowledge-versions-mobile.png') });
  await writeFile(evidence('knowledge-versions-live.json'), JSON.stringify({ stack: 'Real React/Vite → Spring Boot HTTP → disposable MySQL; Mock AI; no synthetic API', checks, documentId: doc.id, trainingSessionId: session.sessionId, citationHref, snapshot: session.agent.training.knowledge }, null, 2));
} catch (error) {
  console.error((await readFile(stack.dir + '/backend.log', 'utf8')).split('\n').slice(-15).join('\n'));
  throw error;
} finally { await browser?.close(); await stack.cleanup(); }
