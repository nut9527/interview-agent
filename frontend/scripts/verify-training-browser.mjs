// Production UI acceptance for focused training and linked retry comparison; synthetic API only.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, extname, join, sep } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = join(root, 'frontend/dist'), evidence = join(root, 'docs/evidence');
await mkdir(evidence, { recursive: true });
await mkdir(join(root, '.runtime'), { recursive: true });
const temporary = await mkdtemp(join(root, '.runtime/training-browser-'));
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const path = resolve(dist, '.' + (extname(pathname) ? pathname : '/index.html'));
    if (!path.startsWith(dist + sep)) return response.writeHead(403).end();
    response.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream');
    response.end(await readFile(path));
  } catch { response.writeHead(404).end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
const decision = { level: 'PARTIAL', evidence: '我负责性能优化', gap: '缺少压测基线与 P99 延迟的前后对照',
  action: 'FINISH', nextCompetencyId: 'performance', reason: '需要核对测量依据', practice: '补充测量记录', probe: 'NONE', intent: '复盘', questionCompetencyId: '' };
const source = { sessionId: 'source', targetRole: '性能专项训练', resumeId: null, resumeRevision: null, resumeSnapshot: '', jobId: null,
  jobSnapshot: '', mode: 'BACKEND_BASICS', difficulty: 'ADVANCED', totalRounds: 3, state: 'FINISHED', turnCount: 3, answerLeaseUntil: null,
  turns: [{ speaker: 'interviewer', content: '你如何验证性能优化？' }, { speaker: 'user', content: '我负责性能优化，采用了缓存。' },
    { speaker: 'interviewer', content: '本轮练习完成。' }],
  agent: { version: 'interview-training-v1', executionMode: 'mock', activeCompetencyId: 'performance',
    plan: [{ id: 'performance', name: '性能分析', objective: '说明性能测量、瓶颈与优化验证' }],
    evaluations: [{ round: 1, competencyId: 'performance', decision }], training: { topic: 'PERFORMANCE', retry: null } } };
const sessions = { source }, writes = [], errors = [], checks = [];
let context, failStart = false;
const summary = session => ({ sessionId: session.sessionId, targetRole: session.targetRole, resumeId: null, mode: session.mode,
  totalRounds: session.totalRounds, state: session.state, turnCount: session.turnCount, answeredCount: session.state === 'FINISHED' ? 3 : 0,
  preview: '性能验证练习', createdAt: '2026-10-28T04:00:00Z', updatedAt: '2026-10-28T04:00:00Z' });
try {
  context = await chromium.launchPersistentContext(join(temporary, 'profile'), { channel: 'msedge', headless: true,
    env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1440, height: 1200 } });
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-training-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'test', displayName: '训练验收', targetRole: 'Java', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, data, message: '' } });
    if (url.pathname === '/api/resumes') return ok([]);
    if (url.pathname === '/api/target-jobs') return ok({ items: [], total: 0, page: 1, size: 20 });
    if (url.pathname === '/api/interviews/sessions') {
      if (request.method() === 'GET') return ok({ items: [summary(source)], total: 1, page: 1, size: 20 });
      const body = request.postDataJSON(); writes.push(body);
      if (failStart) return route.fulfill({ status: 400, json: { success: false, message: '模拟复练创建失败', data: null } });
      const retry = body.retrySessionId ? { sourceSessionId: 'source', sourceRound: body.retryRound, competencyId: 'performance',
        competencyName: '性能分析', question: source.turns[0].content, answer: source.turns[1].content, level: 'PARTIAL', evidence: decision.evidence, gap: decision.gap } : null;
      const session = { ...source, sessionId: `new-${writes.length}`, targetRole: body.targetRole, totalRounds: body.totalRounds,
        state: 'WAITING_ANSWER', turnCount: 1, turns: [{ speaker: 'interviewer', content: '请补充性能压测基线和前后测量依据。' }],
        agent: { ...source.agent, evaluations: [], training: { topic: body.trainingTopic ?? null, retry } } };
      sessions[session.sessionId] = session; return ok(session);
    }
    const match = url.pathname.match(/^\/api\/interviews\/sessions\/([^/]+)(\/retries|\/answers)?$/);
    if (match) {
      if (match[2] === '/retries') {
        const items = Object.values(sessions).filter(item => item.agent.training?.retry?.sourceSessionId === match[1]).map(summary);
        return ok({ items, total: items.length, page: 1, size: 20 });
      }
      const session = sessions[match[1]];
      if (match[2] === '/answers') {
        const body = request.postDataJSON(); const round = session.turnCount;
        session.turns.push({ speaker: 'user', content: body.answer }, { speaker: 'interviewer', content: round === 3 ? '练习完成' : `继续核对性能证据 ${round}` });
        session.agent.evaluations.push({ round, competencyId: 'performance', decision: { ...decision, evidence: body.answer.slice(0, 20), gap: '仍缺少压测环境与样本量说明' } });
        session.state = round === 3 ? 'FINISHED' : 'WAITING_ANSWER'; session.turnCount = Math.min(3, round + 1);
      }
      return ok(session);
    }
    if (url.pathname.startsWith('/api/')) { errors.push(url.pathname); return route.fulfill({ status: 404, body: '{}' }); }
    return route.continue();
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/interview');
  await page.getByText('专项训练', { exact: true }).click();
  for (const name of ['数据库', '并发', '性能', '项目深挖']) assert.equal(await page.getByRole('radio', { name: new RegExp(name) }).count(), 1);
  await page.getByText('性能', { exact: true }).click();
  await page.getByText('挑战', { exact: true }).click();
  await page.getByText('6 轮 · 标准', { exact: true }).click();
  await page.getByTitle('4 轮', { exact: true }).click();
  assert.equal(await page.getByRole('radio', { name: /^性能/ }).isChecked(), true);
  assert.equal(await page.getByRole('radio', { name: /挑战/ }).isChecked(), true);
  await page.screenshot({ path: join(evidence, '2026-10-28-training-settings.png'), fullPage: true });
  await page.getByRole('button', { name: '开始专项训练', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=new-1');
  assert.equal(writes[0].trainingTopic, 'PERFORMANCE'); assert.equal(writes[0].difficulty, 'ADVANCED'); assert.equal(writes[0].totalRounds, 4);
  assert.equal(writes[0].resumeId, undefined); assert.equal(writes[0].jobDescription, undefined);
  checks.push('Four topics; selected difficulty and four rounds submit without resume/JD.');
  await page.goto(origin + '/interview?sessionId=source');
  failStart = true;
  await page.getByRole('button', { name: '复练此能力 · 3 轮' }).click();
  await page.getByText('模拟复练创建失败', { exact: false }).waitFor();
  assert.ok(page.url().endsWith('sessionId=source'));
  failStart = false;
  await page.getByRole('button', { name: '复练此能力 · 3 轮' }).click();
  await page.waitForURL('**/interview?sessionId=new-3');
  assert.equal(writes[2].retrySessionId, 'source'); assert.equal(writes[2].retryRound, 1);
  assert.equal(writes[2].trainingTopic, undefined); assert.equal(writes[2].resumeId, undefined);
  for (let round = 1; round <= 3; round++) {
    await page.getByRole('textbox', { name: '面试回答' }).fill(`我测得第 ${round} 次 P99 延迟从 200ms 降到 80ms，并保留了性能对照记录。`);
    await page.getByRole('button', { name: /提交回答/ }).click();
    await page.getByRole('region', { name: '复练前后对照' }).getByText(`复练 · 第 ${round} 轮`, { exact: false }).waitFor();
  }
  const comparison = page.getByRole('region', { name: '复练前后对照' });
  await comparison.getByText(source.turns[1].content, { exact: true }).waitFor();
  assert.equal(await comparison.getByText('仍缺少压测环境与样本量说明', { exact: false }).count(), 3);
  await page.reload(); await comparison.getByRole('heading', { name: /前后对照/ }).waitFor();
  await comparison.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(evidence, '2026-10-28-training-comparison.png'), fullPage: true });
  checks.push('Failed retry preserves source; one-click retry carries only source reference; all new answers, evidence and gaps survive reload.');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width <= 65);
  await comparison.scrollIntoViewIfNeeded();
  assert.ok((await comparison.boundingBox()).width >= 260);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: join(evidence, '2026-10-28-training-mobile.png'), fullPage: true });
  await comparison.getByRole('link', { name: '打开原会话' }).click();
  await page.getByRole('region', { name: '关联复练记录' }).getByRole('link', { name: /查看复练与对照/ }).waitFor();
  checks.push('Mobile comparison fits viewport; source session links back to its retry.');
  assert.deepEqual(errors, []);
  await writeFile(join(evidence, '2026-10-28-training-browser.json'), JSON.stringify({ checks, errors, api: 'synthetic local', viewport: [1440, 390] }, null, 2) + '\n');
  console.log(JSON.stringify({ checks, errors }, null, 2));
} finally {
  await context?.close(); await new Promise(done => server.close(done));
  const actual = await realpath(temporary), allowed = await realpath(join(root, '.runtime'));
  if (!actual.startsWith(allowed + sep)) throw new Error('Refusing cleanup outside this run');
  await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
}
