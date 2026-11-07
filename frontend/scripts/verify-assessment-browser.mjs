// 生产界面使用真实 MySQL 集成测试导出的合成会话。
// API 传输被拦截，不代表真实供应商或后端链路质量验收。
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, mkdtemp, rm, realpath, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, resolve, extname, sep } from 'node:path';
import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';
const chromium = await loadChromium();
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = resolve(root, process.env.DIST_DIR || 'frontend/dist');
const detailed = process.env.DETAILED_ASSESSMENT === 'true';
const withdrawn = process.env.REVOKED_ASSESSMENT === 'true';
const stem = withdrawn ? 'revoked-assessment' : detailed ? 'detailed-assessment' : 'assessment';
const original = JSON.parse(await readFile(join(root, withdrawn ? 'backend/target/revoked-interview-session.json' : detailed ? 'backend/target/p1-assessment-session.json' : 'backend/target/m0-assessment-session.json'), 'utf8'));
const evidence = resolve(root, process.env.EVIDENCE_DIR || 'docs/evidence'); await mkdir(evidence, { recursive: true });
const parent = join(root, '.runtime'); await mkdir(parent, { recursive: true });
const dir = await mkdtemp(join(parent, 'assessment-browser-'));
let session = structuredClone(original), browser;
const errors = [], checks = [];
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
  browser = await chromium.launchPersistentContext(join(dir, 'profile'), { ...browserLaunchOptions(), headless: true,
    env: { ...process.env, TMP: dir, TEMP: dir }, viewport: { width: 1360, height: 980 } });
  browser.setDefaultTimeout(15000);
  await browser.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-assessment-token');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'evidence', displayName: '来源验收', targetRole: 'Java', skillTags: [], demo: false }));
  });
  await browser.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const ok = data => route.fulfill({ json: { success: true, data, message: '' } });
    if (url.pathname === '/api/interviews/sessions/' + session.sessionId) return ok(session);
    if (url.pathname.endsWith('/resources') || url.pathname === '/api/resumes' || url.pathname === '/api/interview-runs') return ok([]);
    if (['/api/interviews/sessions', '/api/interviews/training-tasks', '/api/target-jobs', '/api/knowledge/documents'].includes(url.pathname) || url.pathname.endsWith('/retries'))
      return ok({ items: [], page: 1, size: 20, total: 0 });
    errors.push(`Unexpected API: ${url.pathname}`); return route.fulfill({ status: 404, body: '{}' });
  });
  const page = await browser.newPage(); page.on('pageerror', e => errors.push(e.message));
  const open = async () => {
    await page.goto(origin + '/interview?sessionId=' + session.sessionId);
    if (session.state === 'FINISHED' && session.agent?.evaluations.some(e => e.assessment)) {
      await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
      const summary = page.locator('summary').filter({ hasText: /^查看第 \d+ 轮分项观察与来源$/ }).first();
      await summary.waitFor();
      assert.equal(await summary.evaluate(el => el.parentElement.open), false);
      await summary.click();
    }
  };
  await open();
  await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
  if (withdrawn) {
    const notice = page.getByText('本场资料已删除，相关评价已撤销。回答记录保留；请更换材料新建训练。', { exact: true });
    await notice.waitFor();
    assert.equal(await page.getByRole('button', { name: /复练此能力/ }).first().isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '加入训练计划', exact: true }).first().isDisabled(), true);
    assert.equal(await page.getByText('事务的唯一约束需要核对请求标识及冲突后的原结果。', { exact: true }).count(), 0);
    await notice.scrollIntoViewIfNeeded();
    await page.screenshot({ path: join(evidence, `${stem}-desktop.png`), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width <= 65);
    await notice.scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    await page.screenshot({ path: join(evidence, `${stem}-mobile.png`), fullPage: true });
    // UI 契约变体由 InterviewSourceGuardTest 使用实际详细证据独立校验。
    session = structuredClone(original);
    for (const evaluation of session.agent.evaluations) {
      const item = evaluation.assessment.detail?.items.find(i => i.dimension === 'TECHNICAL_CORRECTNESS');
      if (item) { item.status = 'OBSERVED'; item.claim.semanticCheck = 'SOURCE_REVOKED'; item.claim.status = 'INSUFFICIENT'; }
    }
    await open(); await page.getByText('来源已撤销', { exact: true }).first().waitFor();
    session = structuredClone(original); session.state = 'WAITING_ANSWER'; session.turnCount = 2; session.agent = null;
    session.experience.format = 'FORMAL'; session.turns = session.turns.slice(0,3).filter(t => !t.content.startsWith('即时反馈：'));
    await open(); await notice.waitFor();
    assert.equal(await page.getByLabel('面试回答').isEditable(), false);
    assert.equal(await page.getByLabel('面试回答').isEnabled(), true); // retained draft can still be focused and copied
    assert.equal(await page.locator('summary').filter({ hasText: '查看参考资料来源' }).count(), 0);
    checks.push('Real-DB withdrawn projection preserves history, hides source text, disables retry/task/answer actions and retains the formal-mode warning at 390px.');
    assert.deepEqual(errors, []);
    await writeFile(join(evidence, `${stem}-browser.json`), JSON.stringify({
      scope: 'Production build; intercepted API replay of real-MySQL revocation fixture', checks, browserErrors: errors }, null, 2) + '\n');
    console.log(JSON.stringify({ checks, browserErrors: errors }, null, 2));
  } else {
  assert.ok(await page.getByText('职责边界', { exact: true }).count() >= 1);
  assert.ok(await page.getByText('因果解释', { exact: true }).count() >= 1);
  const summary = page.locator('summary').filter({ hasText: '查看回答来源' }).first(); await summary.click();
  const mark = page.locator('details[open] mark').first(); await mark.waitFor();
  const savedAssessment = original.agent.evaluations[0].assessment;
  assert.equal(await mark.textContent(), (detailed ? savedAssessment.detail : savedAssessment).evidence[0].quote);
  await summary.focus(); await page.keyboard.press('Enter'); assert.equal(await mark.isVisible(), false);
  await page.keyboard.press('Enter'); assert.equal(await mark.isVisible(), true);
  await page.getByRole('heading', { name: '回答证据与练习建议' }).scrollIntoViewIfNeeded();
  if (detailed) {
    for (const label of ['证据充分程度', '技术正确性', '因果解释', '方案权衡', '职责边界', '表达清晰度'])
      assert.ok(await page.getByText(label, { exact: true }).count() >= 1);
    assert.ok(await page.getByText('来源对照有争议，保留待复核。', { exact: true }).count() >= 1);
    await page.locator('summary').filter({ hasText: '查看参考资料来源' }).first().click();
    assert.ok(await page.getByText('这是本场归档的对照资料，不能证明候选人已实际实施。', { exact: true }).count() >= 1);
    checks.push('All six dimensions render; conflicting references and correction proposals stay disputed and require review.');
    await mark.scrollIntoViewIfNeeded();
  }
  await page.screenshot({ path: join(evidence, `${stem}-desktop.png`), fullPage: true });
  checks.push('Two independent observations expand to the exact raw answer and limits; keyboard Enter toggles source.');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width <= 65);
  await mark.scrollIntoViewIfNeeded();
  assert.equal(await page.getByRole('region', { name: detailed ? /^第 \d+ 轮六维观察$/ : /^第 \d+ 轮分项观察$/ }).first()
    .evaluate(el => el.scrollWidth > el.clientWidth + 2), false);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
  await page.screenshot({ path: join(evidence, `${stem}-mobile.png`), fullPage: true });
  checks.push('390px viewport has no horizontal overflow with full hashes and highlighted raw source.');
  await page.reload(); await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
  assert.ok(await page.locator('summary').filter({ hasText: '查看回答来源' }).count() >= 2);
  checks.push('Reload preserves persisted observations and source locations.');
  session = structuredClone(original); session.state = 'WAITING_ANSWER'; session.turnCount = 2; session.agent = null;
  session.turns = session.turns.slice(0, 3).filter(t => !t.content.startsWith('即时反馈：'));
  await open(); await page.getByLabel('面试回答').waitFor();
  assert.equal(await page.locator('summary').filter({ hasText: '查看回答来源' }).count(), 0);
  assert.equal(await page.getByText('职责边界', { exact: true }).count(), 0);
  checks.push('An unfinished formal projection exposes neither observations nor source-reference controls.');
  session = structuredClone(original);
  for (const e of session.agent.evaluations) e.assessment = null;
  await open(); await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
  assert.ok(await page.getByText('此历史轮次未记录独立分项评价，不反推评分。', { exact: true }).count() >= 1);
  checks.push('Legacy results remain readable and explicitly unassessed.');
  session = structuredClone(original);
  for (const e of session.agent.evaluations) {
    const result = detailed ? e.assessment.detail : e.assessment;
    result.evidence = [];
    for (const item of result.items) {
      item.status = 'NOT_OBSERVED'; item.claim.status = 'INSUFFICIENT'; item.claim.evidenceIds = [];
      item.claim.claimText = '信息不足，未观察到此行为';
      if (detailed) { item.citations = []; item.majorCorrection = false; item.technicalReview = 'INSUFFICIENT'; }
    }
  }
  await open(); await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
  assert.ok(await page.getByText('未观察到', { exact: true }).count() >= 2);
  assert.equal(await page.locator('summary').filter({ hasText: '查看回答来源' }).count(), 0);
  checks.push('Insufficient answers show unknown observations without fabricated evidence.');
  session = structuredClone(original);
  for (const evaluation of session.agent.evaluations) {
    const invalidAssessment = evaluation.assessment;
    (detailed ? invalidAssessment.detail : invalidAssessment).evidence[0].quote = 'not the accepted answer';
  }
  await open(); await page.getByRole('heading', { name: '回答证据与练习建议' }).waitFor();
  assert.ok(await page.getByText('来源已失效或无法定位，不能据此确认观察。', { exact: true }).count() >= 1);
  await page.getByText('来源已失效或无法定位，不能据此确认观察。', { exact: true }).first().waitFor();
  checks.push('A mismatched source is visibly unavailable and never highlighted at another location.');
  assert.deepEqual(errors, []);
  await writeFile(join(evidence, `${stem}-browser.json`), JSON.stringify({
    scope: 'Production build, intercepted API replay of real-MySQL controlled-model fixture; no external model', checks, browserErrors: errors }, null, 2) + '\n');
  console.log(JSON.stringify({ checks, browserErrors: errors }, null, 2));
  }
} finally {
  await browser?.close(); await new Promise(done => server.close(done));
  const actual = await realpath(dir), allowed = await realpath(parent);
  if (!actual.startsWith(allowed + sep)) throw new Error('Refusing cleanup outside owned project directory');
  await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
}
