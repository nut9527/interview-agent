import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir, mkdtemp, realpath, rm } from 'node:fs/promises';
import { resolve, join, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';
const chromium = await loadChromium();
const root = fileURLToPath(new URL('../../', import.meta.url)), dist = join(root, 'frontend/dist');
const parent = join(root, '.runtime'), evidence = join(root, 'docs/evidence');
await mkdir(parent, { recursive: true });
const temporary = await mkdtemp(join(parent, 'training-sources-browser-'));
const q = { id: 'source-question', title: '幂等写入与失败恢复', version: 2, topic: 'DATABASE', competencyId: 'storage', difficulty: 'STANDARD', tags: [], question: '并发重复请求如何保证结果一致？', referenceAnswer: '使用唯一请求号与事务。', keyPoints: [], commonMistakes: [], followUps: [], sourceType: 'ORIGINAL', updatedAt: '2026-10-31T00:00:00Z' };
const doc = { id: 'source-document', title: '数据库学习笔记', sourceName: '合成验收资料', version: 3, content: '请求号需要唯一约束。', chunks: [], updatedAt: '2026-10-31T00:00:00Z' };
const tasks = [], calls = [], errors = [], checks = [];
let browser, failKnowledge = true;
const server = createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const path = resolve(dist, '.' + (extname(pathname) ? pathname : '/index.html'));
    if (!path.startsWith(dist + sep)) return res.writeHead(403).end();
    res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(path)] || 'application/octet-stream');
    res.end(await readFile(path));
  } catch { res.writeHead(404).end(); }
});
try {
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launchPersistentContext(join(temporary, 'profile'), { ...browserLaunchOptions(), headless: true,
    args: ['--disable-gpu', '--disable-gpu-shader-disk-cache'], env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1360, height: 1000 } });
  browser.setDefaultTimeout(15000);
  await browser.addInitScript(() => { localStorage.setItem('token', 'synthetic-source-token'); localStorage.setItem('user', JSON.stringify({ id: 1, username: 'source-fixture', displayName: '来源训练验收', skillTags: [] })); });
  await browser.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url());
    if (url.origin !== origin) return route.abort();
    const path = url.pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    if (path === '/api/questions/source-question') return ok(q);
    if (path === '/api/questions/metadata') return ok({ topics: [{ value: 'DATABASE', label: '数据库' }], difficulties: [{ value: 'STANDARD', label: '标准' }] });
    if (path === '/api/questions') return ok({ items: [q], total: 1, page: 1, size: 100 });
    if (path === '/api/knowledge/documents') return ok({ items: [doc], total: 1, page: 1, size: 100 });
    if (path === '/api/knowledge/documents/source-document') return ok(doc);
    if (['/api/knowledge/organization', '/api/knowledge/imports/jobs', '/api/learning/goals'].includes(path)) return ok([]);
    if (path === '/api/knowledge/answers') return ok({ items: [], total: 0, page: 1, size: 20 });
    if (path === '/api/learning/observations') return ok({ observations: [], preferences: { personalizationEnabled: false } });
    if (path === '/api/interviews/training-tasks') return ok({ items: tasks, total: tasks.length, page: 1, size: 20 });
    if (path === '/api/interviews/training-tasks/from-source') {
      const body = req.postDataJSON(); calls.push(body);
      assert.ok(body.requestId); assert.equal(body.sourceSessionId, undefined);
      if (body.sourceType === 'KNOWLEDGE' && failKnowledge) { failKnowledge = false; return route.fulfill({ status: 409, json: { code: 'CONFLICT', message: '资料版本已变化，请重新核对' } }); }
      const existing = tasks.find(t => t.requestId === body.requestId); if (existing) return ok(existing);
      const task = { id: 'task-' + tasks.length, sourceType: body.sourceType, sourceSessionId: '', sourceRound: 0, requestId: body.requestId,
        questionId: body.questionId || '', questionVersion: body.questionVersion, sourceDocumentId: body.documentId, sourceDocumentVersion: body.documentVersion,
        trainingTopic: body.trainingTopic, title: body.sourceType === 'QUESTION' ? q.title : doc.title, gap: '三轮实际作答后核对来源证据。',
        status: 'TODO', activeSessionId: null, answeredCount: 0, totalRounds: 3, createdAt: '2026-10-31T00:00:00Z' };
      tasks.push(task); return ok(task);
    }
    if (path.endsWith('/status')) { const task = tasks.find(t => path.includes('/' + t.id + '/')); assert.ok(task); task.status = req.postDataJSON().action === 'PAUSE' ? 'PAUSED' : 'TODO'; return ok(task); }
    errors.push('Unexpected API: ' + path); return route.fulfill({ status: 404, body: '{}' });
  });
  const page = await browser.newPage(); page.on('pageerror', e => errors.push(e.message));
  await page.goto(origin + '/questions/source-question');
  await page.getByRole('button', { name: '加入训练计划', exact: true }).click();
  await page.getByRole('button', { name: '确认加入', exact: true }).click();
  await page.getByText('任务已保存', { exact: false }).waitFor();
  assert.equal(calls[0].questionVersion, 2); assert.equal(tasks.length, 1);
  await page.getByRole('button', { name: '完成', exact: true }).click();
  await page.getByRole('button', { name: '加入训练计划', exact: true }).click();
  await page.getByRole('button', { name: '确认加入', exact: true }).click();
  await page.getByText('任务已保存', { exact: false }).waitFor();
  assert.equal(calls[1].requestId, calls[0].requestId); assert.equal(tasks.length, 1);
  checks.push('question creation pins visible version and retries reuse the request key');
  await page.goto(origin + '/knowledge?document=source-document');
  await page.getByRole('button', { name: '加入训练计划', exact: true }).click();
  await page.getByLabel('训练方向', { exact: true }).first().click();
  await page.getByTitle('并发', { exact: true }).click();
  await page.getByRole('button', { name: '确认加入', exact: true }).click();
  await page.getByText('资料版本已变化，请重新核对', { exact: true }).waitFor();
  await page.getByRole('button', { name: '确认加入', exact: true }).click();
  await page.getByText('任务已保存', { exact: false }).waitFor();
  assert.equal(calls[2].requestId, calls[3].requestId); assert.equal(calls[3].documentVersion, 3); assert.equal(calls[3].trainingTopic, 'CONCURRENCY');
  checks.push('knowledge creation pins version and direction; conflict remains visible and retry is stable');
  await page.goto(origin + '/learning/goals');
  await page.getByRole('link', { name: '题库 · v2', exact: true }).waitFor();
  await page.getByRole('link', { name: '知识资料 · v3', exact: true }).waitFor();
  assert.ok((await page.getByRole('link', { name: '知识资料 · v3', exact: true }).getAttribute('href')).includes('version=3'));
  await page.getByRole('button', { name: '暂停任务', exact: true }).first().click();
  await page.getByRole('button', { name: '继续任务', exact: true }).click();
  await page.reload(); await page.getByRole('link', { name: '题库 · v2', exact: true }).waitFor();
  checks.push('refresh preserves source links and pause/resume actions');
  await page.getByLabel('个人训练计划', { exact: true }).screenshot({ path: join(evidence, 'training-sources-desktop.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width <= 65);
  assert.equal(await page.getByLabel('个人训练计划', { exact: true }).evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await page.getByLabel('个人训练计划', { exact: true }).screenshot({ path: join(evidence, 'training-sources-mobile.png') });
  checks.push('390px training plan has no horizontal overflow'); assert.deepEqual(errors, []);
  await writeFile(join(evidence, 'training-sources-browser.json'), JSON.stringify({ transport: 'production UI with synthetic intercepted API; database behavior separately verified by TrainingSourcesIT', checks, errors, realProviderCalls: 0 }, null, 2) + '\n');
  console.log(JSON.stringify({ checks, errors }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(done => server.close(done));
  const actual = await realpath(temporary), allowed = await realpath(parent);
  if (!actual.startsWith(allowed + sep)) throw new Error('Refusing cleanup outside owned project directory');
  await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 300 });
}
