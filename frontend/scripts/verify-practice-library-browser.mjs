// 生产界面使用本地合成 API，不调用后端或模型。
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, extname, join, sep } from 'node:path';

import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';
const chromium = await loadChromium();
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = resolve(root, process.env.DIST_DIR || 'frontend/dist');
const evidence = resolve(root, process.env.EVIDENCE_DIR || 'docs/evidence');
await mkdir(evidence, { recursive: true });
await mkdir(join(root, '.runtime'), { recursive: true });
const temporary = await mkdtemp(join(root, '.runtime/practice-library-browser-'));
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const path = resolve(dist, '.' + (extname(pathname) ? pathname : '/index.html'));
    if (!path.startsWith(dist + sep)) { response.writeHead(403).end(); return; }
    response.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream');
    response.end(await readFile(path));
  } catch { response.writeHead(404).end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
const content = '# 数据库笔记\n\n索引减少扫描行数，但写入时需要维护索引。';
const importContent = content + '\n资料核对正文：分页修改应保留其余全部内容。\n'.repeat(8000);
let expectedImportContent = importContent;
const document = { id: 'doc-1', title: '数据库笔记', sourceName: 'database.md', version: 1,
  updatedAt: '2026-11-01T00:00:00Z', content,
  chunks: [{ id: 'chunk-1', ordinal: 1, heading: '数据库笔记', startOffset: 0, endOffset: content.length, startLine: 1, endLine: 3, content }] };
for (let index = 2; index <= 48; index++) {
  const text = `## 第 ${index} 节：索引场景\n\n- **机制**：索引减少扫描行数。\n- 写入时需要维护索引。\n\n\`\`\`sql\nSELECT * FROM records WHERE id = ${index};\n\`\`\``;
  const startOffset = document.content.length + 2;
  document.content += '\n\n' + text;
  document.chunks.push({ id: `chunk-${index}`, ordinal: index, heading: `索引场景 ${index}`, startOffset, endOffset: document.content.length, startLine: index * 10, endLine: index * 10 + 8, content: text });
}
const question = { id: 'test-1', confirmed: false, executionMode: 'mock',
  sources: [{ documentId: document.id, title: document.title, sourceName: document.sourceName, version: 1, available: true }],
  question: { stem: '增加索引有什么取舍？', point: '读写成本', referencePoints: ['索引减少扫描行数', '写入时需要维护索引'],
    citations: [{ documentId: document.id, title: document.title, version: 1, chunkId: 'chunk-1', startLine: 1, endLine: 3, quote: content }] } };
const job = { id: 'import-1', sourceName: 'database.md', status: 'READY', completedPages: 0, totalPages: 0, message: '', expiresAt: '2099-01-01T00:00:00Z' };
const checks = [], errors = [], generations = [], starts = [];
let context, imported = false, uploaded = false, generated = false, attempts = 0, organizationRequests = 0, session;
const paged = items => ({ items, page: 1, size: 20, total: items.length });
try {
  context = await chromium.launchPersistentContext(join(temporary, 'profile'), { ...browserLaunchOptions(), headless: true,
    env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1440, height: 1100 } });
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-library-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'test', displayName: '题目资料验收', targetRole: 'Java', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, data, message: '' } });
    if (path === '/api/questions/metadata') return ok({ topics: [], difficulties: [] });
    if (path === '/api/questions/practice-status') return ok([]);
    if (path === '/api/questions') return ok(paged([]));
    if (path === '/api/knowledge/organization') { organizationRequests++; return ok([]); }
    if (path === '/api/knowledge/imports/jobs') {
      if (request.method() === 'POST') { uploaded = true; return ok(job); }
      return ok(uploaded ? [job] : []);
    }
    if (path === '/api/knowledge/imports/jobs/import-1/result') return ok({ title: document.title, sourceName: document.sourceName, content: importContent,
      warnings: ['有 7 页仅包含图片，未提取文字。请补全这些页的内容后确认。', '请对照原文件核对文序、乱码和缺失内容；图片不做 OCR，引用以确认入库的正文为准。'] });
    if (path === '/api/knowledge/imports/jobs/import-1/confirm') {
      assert.equal(request.postDataJSON().confirmed, true);
      assert.equal(request.postDataJSON().content, expectedImportContent);
      imported = true; job.status = 'IMPORTED'; return ok(document);
    }
    if (path === '/api/knowledge/documents') return ok(paged(imported ? [document] : []));
    if (path === '/api/knowledge/documents/doc-1' || path === '/api/knowledge/documents/doc-1/versions/1') return ok(document);
    if (path === '/api/knowledge/self-tests/generate') {
      generations.push(request.postDataJSON()); attempts++;
      if (attempts === 1) return route.fulfill({ status: 503, json: { success: false, message: '生成暂时不可用，请重试' } });
      generated = true; return ok([{ ...question, confirmed: false }]);
    }
    if (path === '/api/knowledge/self-tests') return ok(paged(generated && String(question.confirmed) === url.searchParams.get('confirmed') ? [question] : []));
    if (path === '/api/knowledge/self-tests/test-1/confirm') {
      const body = request.postDataJSON(); assert.equal(body.confirmed, true);
      question.question = { ...question.question, stem: body.stem, point: body.point, referencePoints: body.referencePoints };
      question.confirmed = true; return ok(question);
    }
    if (path === '/api/resumes' || path === '/api/interview-runs' || path.endsWith('/runs')) return ok([]);
    if (path === '/api/target-jobs' || path === '/api/interviews/sessions') return ok(paged([]));
    if (path === '/api/interviews/start-commands') {
      const command = request.postDataJSON(), body = command.configuration; starts.push(body);
      session = { sessionId: 'library-session', targetRole: body.targetRole, mode: body.mode, difficulty: body.difficulty,
        totalRounds: body.totalRounds, trainingTopic: body.trainingTopic, state: 'WAITING_ANSWER', turnCount: 1,
        turns: [{ speaker: 'interviewer', content: '请结合资料解释索引的取舍。' }] };
      return ok({ runId: 'library-run', sessionId: session.sessionId, requestId: command.requestId, operation: 'START', status: 'SUCCEEDED', phase: 'COMMIT', errorCode: null });
    }
    if (path === '/api/interviews/sessions/library-session') return ok(session);
    if (path.startsWith('/api/')) { errors.push(`Unexpected API: ${path}`); return route.fulfill({ status: 404, body: '{}' }); }
    return route.continue();
  });
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/questions');
  await page.getByRole('heading', { name: '精选题目', exact: true }).waitFor();
  const tabs = page.getByRole('navigation', { name: '题目与资料' });
  assert.equal(await tabs.getByRole('link').count(), 3);
  await tabs.getByRole('link', { name: '资料与出题', exact: true }).click();
  await page.getByRole('heading', { name: '从资料生成面试练习题', exact: true }).waitFor();
  assert.equal(organizationRequests, 0);
  assert.equal(await page.getByRole('textbox', { name: '资料问题' }).isVisible(), false);
  await page.locator('input[type=file]').setInputFiles({ name: 'database.md', mimeType: 'text/markdown', buffer: Buffer.from(content) });
  const importDialog = page.getByRole('dialog', { name: '导入前核对' });
  await importDialog.waitFor();
  assert.equal(await importDialog.getByRole('button', { name: '确认入库为 v1' }).isDisabled(), true);
  for (const viewport of [{ width: 1366, height: 768 }, { width: 1100, height: 620 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    for (const locator of [importDialog.locator('.ant-modal-content'), importDialog.getByRole('checkbox'), importDialog.getByRole('button', { name: '确认入库为 v1' })]) {
      const box = await locator.boundingBox();
      assert.ok(box && box.y >= 0 && box.y + box.height <= viewport.height + 1, `Import controls must fit ${JSON.stringify(viewport)}: ${JSON.stringify(box)}`);
      assert.ok(box.x >= 0 && box.x + box.width <= viewport.width + 1);
    }
    assert.equal(await importDialog.locator('.ant-modal-body').evaluate(el => el.scrollWidth > el.clientWidth), false);
    await page.screenshot({ path: join(evidence, `import-review-${viewport.width}x${viewport.height}.png`) });
  }
  await page.setViewportSize({ width: 1366, height: 768 });
  const editor = importDialog.getByRole('textbox', { name: '确认正文', exact: true });
  const firstPage = await editor.inputValue();
  await importDialog.getByRole('checkbox').check();
  await editor.fill('核对补充：' + firstPage);
  expectedImportContent = '核对补充：' + importContent;
  assert.equal(await importDialog.getByRole('checkbox').isChecked(), false);
  await importDialog.locator('.ant-pagination-next button').click();
  await importDialog.locator('.ant-pagination-prev button').click();
  assert.equal(await editor.inputValue(), '核对补充：' + firstPage);
  checks.push('Long import with multiple warnings fits desktop, short/zoomed, mobile and landscape viewports; review controls stay visible and paged edits are preserved.');
  await importDialog.getByRole('checkbox').check();
  await importDialog.getByRole('button', { name: '确认入库为 v1' }).click();
  await importDialog.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '从这份资料出题', exact: true }).waitFor();
  checks.push('Unified library tabs; upload review required before document import; optional tools stay folded.');

  const directory = page.getByRole('navigation', { name: '资料目录' });
  await directory.getByRole('textbox', { name: '筛选目录' }).fill('第 48 节');
  await directory.getByRole('button', { name: /第 48 节/ }).click();
  await page.locator('[id="source-chunk-48"]').waitFor();
  assert.equal(await page.getByRole('region', { name: '资料原文' }).locator('pre code').count(), 1);
  await page.getByRole('switch', { name: '显示原始文本' }).click();
  assert.ok((await page.locator('[id="source-chunk-48"]').innerText()).includes('```sql'));
  await page.goto(origin + '/knowledge?document=doc-1&version=1&chunk=chunk-47');
  await page.locator('[id="source-chunk-47"]').waitFor();
  assert.ok((await page.locator('[id="source-chunk-47"]').innerText()).includes('id = 47'));
  await page.getByRole('region', { name: '资料原文' }).screenshot({ path: join(evidence, 'structured-reader-desktop.png') });
  await page.getByRole('button', { name: '查看当前版本', exact: true }).click();
  checks.push('Existing document directory filters and jumps to late chapters; Markdown lists/code render safely, raw text is available, archived citations select the correct section.');

  await page.getByRole('checkbox', { name: '选择资料：数据库笔记' }).check();
  await page.getByRole('button', { name: '从所选资料生成题目', exact: true }).click();
  const generation = page.getByRole('dialog', { name: '从资料生成面试练习题' });
  await generation.getByText('综合面试 · 场景、原理与取舍', { exact: true }).click();
  await page.getByText('实战应用 · 设计、排错与验证', { exact: true }).click();
  await generation.getByRole('textbox', { name: '考察重点' }).fill('索引场景中的读写权衡');
  await generation.screenshot({ path: join(evidence, 'assessment-settings.png') });
  await generation.getByRole('button', { name: '生成候选题', exact: true }).click();
  await generation.getByText('生成暂时不可用，请重试', { exact: true }).waitFor();
  await generation.getByRole('button', { name: '生成候选题', exact: true }).click();
  const save = generation.getByRole('button', { name: '确认并保存到我的题目', exact: true });
  await save.waitFor(); assert.equal(await save.isDisabled(), true);
  assert.deepEqual(generations[1].sources, [{ documentId: document.id, version: 1, chunkIds: [] }]);
  assert.equal(generations[1].intent, 'PRACTICAL');
  assert.equal(generations[1].difficulty, 'INTERMEDIATE');
  assert.equal(generations[1].focus, '索引场景中的读写权衡');
  // 保持候选未确认，再从持久草稿列表重新打开。
  await generation.getByRole('link', { name: '待确认题目', exact: true }).click();
  await page.waitForURL('**/knowledge/practice?tab=drafts');
  await page.reload();
  await page.getByLabel('自测题干', { exact: true }).waitFor();
  const review = page.getByRole('checkbox', { name: '已逐题核对题干、考察点、参考要点与来源' });
  const confirm = page.getByRole('button', { name: '确认并保存到我的题目', exact: true });
  await review.check();
  await page.getByLabel('自测题干', { exact: true }).fill('什么时候不应继续增加索引？');
  assert.equal(await confirm.isDisabled(), true);
  await review.check(); await confirm.click();
  await page.getByText('没有待确认候选题', { exact: true }).waitFor();
  await tabs.getByRole('link', { name: '我的题目', exact: true }).click();
  await page.waitForURL('**/knowledge/practice');
  await page.reload();
  await page.getByRole('heading', { name: '什么时候不应继续增加索引？', exact: true }).waitFor();
  checks.push('Generation retry retains versioned selection; drafts survive reopening; editing resets review; confirmed question reloads in personal collection.');
  await page.getByText('核对参考要点与来源', { exact: true }).click();
  await page.getByRole('link', { name: /数据库笔记 · v1/ }).click();
  await page.getByText('原文 · v1', { exact: true }).waitFor();
  await page.getByRole('button', { name: '查看当前版本', exact: true }).click();
  await page.getByRole('button', { name: '从这个片段出题', exact: true }).click();
  await generation.getByRole('button', { name: '生成候选题', exact: true }).click();
  await generation.getByRole('button', { name: '确认并保存到我的题目', exact: true }).waitFor();
  assert.deepEqual(generations.at(-1).sources, [{ documentId: document.id, version: 1, chunkIds: ['chunk-1'] }]);
  await generation.getByRole('button', { name: 'Close', exact: true }).click();
  checks.push('Confirmed question links to archived source; explicit snippet generation sends the selected chunk.');

  await page.getByRole('heading', { name: '从资料生成面试练习题', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(evidence, 'library-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width === 64);
  await page.getByRole('heading', { name: '从资料生成面试练习题', exact: true }).scrollIntoViewIfNeeded();
  await tabs.scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.equal(await page.locator('.ant-layout-content').evaluate(el => el.scrollWidth > el.clientWidth), false);
  await page.screenshot({ path: join(evidence, 'library-mobile.png'), fullPage: true });
  await page.getByRole('region', { name: '资料原文' }).screenshot({ path: join(evidence, 'structured-reader-mobile.png') });
  await tabs.getByRole('link', { name: '我的题目', exact: true }).click();
  await page.getByRole('heading', { name: '什么时候不应继续增加索引？', exact: true }).waitFor();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.equal(await page.locator('.ant-layout-content').evaluate(el => el.scrollWidth > el.clientWidth), false);
  await page.goto(origin + '/knowledge?document=doc-1');
  await page.getByRole('link', { name: '使用这份资料进入专项训练', exact: true }).click();
  await page.getByRole('button', { name: '开始专项训练', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=library-session');
  assert.deepEqual(starts[0].knowledgeDocumentIds, [document.id]);
  assert.equal(starts[0].resumeId, undefined);
  checks.push('390px document and personal-question pages have no overflow; imported document starts specialized interview without a resume.');
  assert.deepEqual(errors, []);
  const report = { checks, errors, api: 'synthetic', realDatabaseVerified: false, externalModelVerified: false };
  await writeFile(join(evidence, 'practice-library-browser.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  await context?.close(); await new Promise(done => server.close(done));
  const resolved = await realpath(temporary), runtime = await realpath(join(root, '.runtime'));
  if (!resolved.startsWith(runtime + sep)) throw new Error('Temporary browser path escaped project runtime');
  await rm(resolved, { recursive: true, force: true });
}
