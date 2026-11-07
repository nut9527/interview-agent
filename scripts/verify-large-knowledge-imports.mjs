import { startInterview } from './interview-run-client.mjs';
// 使用合成文件和隔离 MySQL 执行真实 HTTP 与浏览器验收。
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { liveStack } from './live-stack.mjs';
import { splitKnowledgeText } from '../frontend/src/pages/Knowledge/textPages.ts';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const stack = await liveStack(false);
const fixture = name => fileURLToPath(new URL('../backend/target/knowledge-import-fixtures/' + name, import.meta.url));
const evidence = name => fileURLToPath(new URL('../docs/evidence/large-import-' + name, import.meta.url));
const checks = [], metrics = [], runtimeErrors = [];
let browser;
try {
  const api = async (path, body, token, method = body ? 'POST' : 'GET', status = 200) => {
    const response = await fetch(stack.origin + '/api' + path, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(120000),
    });
    const result = await response.json(); assert.equal(response.status, status, JSON.stringify(result)); return result.data;
  };
  const auth = await api('/auth/register', { username: 'large_' + Date.now(), password: 'Acceptance123!', displayName: '大文件导入验收' });
  browser = await chromium.launchPersistentContext(stack.dir + '/browser', {
    channel: 'msedge', headless: true, reducedMotion: 'reduce', env: { ...process.env, TMP: stack.tmp, TEMP: stack.tmp },
    viewport: { width: 1360, height: 1000 },
  });
  const page = await browser.newPage(); page.setDefaultTimeout(30000);
  page.on('pageerror', error => runtimeErrors.push(error.message)); page.on('dialog', dialog => void dialog.accept());
  await page.goto(stack.origin + '/login');
  await page.evaluate(a => { localStorage.setItem('token', a.token); localStorage.setItem('user', JSON.stringify(a.user)); }, auth);
  await page.goto(stack.origin + '/knowledge');
  const upload = async (name, buffer) => {
    const start = Date.now(), response = page.waitForResponse(r => r.url().endsWith('/api/knowledge/imports/jobs') && r.request().method() === 'POST');
    await page.getByLabel('上传资料文件').setInputFiles({ name, mimeType: 'application/octet-stream', buffer });
    const http = await response;
    // 大型 multipart 请求可能挤出 CDP 响应缓存，通过 HTTP 读取持久任务状态。
    const result = http.status() === 200
      ? { data: (await api('/knowledge/imports/jobs', null, auth.token)).find(job => job.sourceName === name) }
      : { message: await page.getByRole('alert').filter({ hasText: '30 MB' }).last().innerText() };
    return { http, result, start, bytes: buffer.length };
  };
  const waitJob = async upload => {
    assert.equal(upload.http.status(), 200, JSON.stringify(upload.result));
    const id = upload.result.data.id, deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      const job = await api('/knowledge/imports/jobs/' + id, null, auth.token);
      if (['READY', 'FAILED', 'CANCELED'].includes(job.status)) {
        metrics.push({ source: job.sourceName, bytes: upload.bytes, pages: job.totalPages, status: job.status, uploadAndParseMillis: Date.now() - upload.start });
        return job;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('Import did not finish before deadline');
  };
  const count = async () => (await api('/knowledge/documents', null, auth.token)).total;
  const review = () => page.getByRole('dialog', { name: '导入前核对' });
  const checkbox = () => review().getByRole('checkbox', { name: '我已核对文序、文字与完整性，确认以上正文入库' });
  const save = async () => {
    await checkbox().check();
    const response = page.waitForResponse(r => r.url().includes('/api/knowledge/imports/jobs/') && r.url().endsWith('/confirm'));
    await review().getByRole('button', { name: '确认入库为 v1', exact: true }).click();
    const http = await response; assert.equal(http.status(), 200); const doc = (await http.json()).data;
    await review().waitFor({ state: 'hidden' }); return doc;
  };
  // PDF 页数上限包含边界值，取消 READY 任务也不得创建文档。
  const thousand = await waitJob(await upload('千页资料.pdf', await readFile(fixture('thousand.pdf'))));
  assert.equal(thousand.status, 'READY'); assert.equal(thousand.totalPages, 1000); assert.equal(thousand.completedPages, 1000);
  await review().waitFor(); assert.equal(await count(), 0);
  await review().getByRole('button', { name: '取消导入', exact: true }).click();
  await review().waitFor({ state: 'hidden' }); assert.equal(await count(), 0);
  assert.equal((await api('/knowledge/imports/jobs/' + thousand.id, null, auth.token)).status, 'CANCELED');
  checks.push('1000 页 PDF 完整解析并记录进度；取消核对不入库');

  const large = await waitJob(await upload('大文件.pdf', await readFile(fixture('large.pdf'))));
  assert.equal(large.status, 'READY'); assert.equal(large.totalPages, 1000); await review().waitFor();
  const corrected = '# 大文件核对\n\n缓存 TTL 180 秒。\n';
  const largePages = splitKnowledgeText((await api('/knowledge/imports/jobs/' + large.id + '/result', null, auth.token)).content);
  largePages[0] = corrected;
  await page.getByLabel('确认正文', { exact: true }).fill(corrected);
  const largeDoc = await save(); assert.equal(largeDoc.content, largePages.join('')); assert.equal(largeDoc.sourceName, '大文件.pdf');
  assert.ok(largeDoc.content.includes('Page 1000'));
  checks.push('超过 25 MB 且包含 1000 页的真实 PDF 经后台解析、修订后保存 v1');

  const longJob = await waitJob(await upload('长篇笔记.docx', await readFile(fixture('long.docx'))));
  assert.equal(longJob.status, 'READY');
  const draft = await api('/knowledge/imports/jobs/' + longJob.id + '/result', null, auth.token);
  assert.ok(draft.content.length > 100000);
  // 刷新后恢复持久 READY 任务，不自动保存文档。
  await page.reload(); await page.getByRole('button', { name: '核对正文', exact: true }).click(); await review().waitFor();
  assert.equal(await count(), 1);
  const pages = splitKnowledgeText(draft.content);
  const first = '首段修订🙂\n' + pages[0]; pages[0] = first;
  await page.getByLabel('确认正文', { exact: true }).fill(first);
  await review().locator('.ant-pagination-next button').click();
  assert.equal(await page.getByLabel('确认正文', { exact: true }).inputValue(), pages[1]);
  pages[1] += '\n补全唯一标记 zephyrmissing。\n';
  await page.getByLabel('确认正文', { exact: true }).fill(pages[1]);
  await review().locator('.ant-pagination-prev button').click();
  assert.equal(await page.getByLabel('确认正文', { exact: true }).inputValue(), first);
  await page.screenshot({ path: evidence('review-desktop.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await checkbox().scrollIntoViewIfNeeded(); await page.screenshot({ path: evidence('review-mobile.png') });
  const longDoc = await save(); assert.equal(longDoc.content, pages.join('')); assert.equal(longDoc.version, 1);
  await page.setViewportSize({ width: 1360, height: 1000 });
  await page.getByLabel('搜索资料', { exact: true }).fill('zephyrmissing');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.getByRole('region', { name: '搜索结果' }).getByRole('link').first().click();
  const chunk = new URL(page.url()).searchParams.get('chunk'); assert.ok(Number(chunk.split(':').at(-1)) > 40);
  await page.locator('[id="source-' + chunk + '"]').waitFor();
  assert.match(await page.locator('[id="source-' + chunk + '"]').innerText(), /zephyrmissing/);
  await page.reload(); await page.locator('[id="source-' + chunk + '"]').waitFor();
  const session = await startInterview(stack.origin, auth.token, { targetRole: '长资料训练', trainingTopic: 'DATABASE', totalRounds: 3, knowledgeDocumentIds: [longDoc.id] });
  assert.equal(session.agent.training.knowledge[0].content, longDoc.content);
  await api('/knowledge/documents/' + longDoc.id + '/content', { content: '缓存 TTL 300 秒。', expectedVersion: 1 }, auth.token, 'PUT');
  assert.equal((await api('/knowledge/documents/' + longDoc.id + '/versions/1', null, auth.token)).content, longDoc.content);
  assert.equal((await api('/interviews/sessions/' + session.sessionId, null, auth.token)).agent.training.knowledge[0].content, longDoc.content);
  checks.push('超 10 万字 DOCX 刷新恢复任务，多段编辑和移动端确认逐字保存；后续片段定位含刷新、版本及训练快照保持准确');

  await page.goto(stack.origin + '/knowledge');
  const before = await count();
  for (const [name, expected] of [['scan.pdf', 'OCR'], ['encrypted.pdf', '加密'], ['encrypted.docx', '加密'], ['empty.docx', '未提取到正文']]) {
    const job = await waitJob(await upload(name, await readFile(fixture(name))));
    assert.equal(job.status, 'FAILED'); assert.ok(job.message.includes(expected), job.message);
    await page.getByRole('alert').filter({ hasText: expected }).last().waitFor();
    assert.equal(await count(), before);
  }
  let oversizedRequests = 0;
  const observeUpload = request => {
    if (request.url().endsWith('/api/knowledge/imports/jobs') && request.method() === 'POST') oversizedRequests++;
  };
  page.on('request', observeUpload);
  await page.getByLabel('上传资料文件').setInputFiles({ name: 'oversize.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(100 * 1024 * 1024 + 1) });
  await page.getByRole('alert').filter({ hasText: '100 MB' }).last().waitFor();
  assert.equal(oversizedRequests, 0);
  page.off('request', observeUpload);
  const other = await api('/auth/register', { username: 'isolate_' + Date.now(), password: 'Acceptance123!', displayName: '隔离账号' });
  assert.deepEqual(await api('/knowledge/imports/jobs', null, other.token), []);
  await api('/knowledge/imports/jobs/' + longJob.id, null, other.token, 'GET', 404);
  assert.deepEqual(runtimeErrors, []);
  checks.push('失败任务提供具体原因且不入库；PDF 超过 100 MB 在上传前拒绝；任务与正文按账号隔离；浏览器无运行错误');
  await writeFile(evidence('browser.json'), JSON.stringify({ stack: 'Real React/Vite -> Spring Boot -> disposable MySQL; Mock AI', checks, metrics, runtimeErrors }, null, 2));
  console.log(JSON.stringify({ checks, metrics }, null, 2));
} catch (error) {
  console.error((await readFile(stack.dir + '/backend.log', 'utf8')).split('\n').slice(-15).join('\n')); throw error;
} finally { await browser?.close(); await stack.cleanup(); }
