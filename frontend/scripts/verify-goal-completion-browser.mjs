// 生产界面回放隔离 MySQL 测试导出，不调用模型或业务数据库。
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, mkdtemp, rm, realpath, writeFile } from 'node:fs/promises';
import { join, resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';
const chromium = await loadChromium();
const root = fileURLToPath(new URL('../../', import.meta.url)), dist = join(root, 'frontend/dist');
const goal = JSON.parse(await readFile(join(root, 'backend/target/goal-plan-fixture.json'), 'utf8'));
goal.plans[0].status = 'PENDING_CONFIRMATION'; goal.plans[0].taskIds = [];
goal.criteriaRevision = 1; goal.revision = 1;
goal.dimensions = ['CAUSAL_EXPLANATION', 'RESPONSIBILITY'];
let tasks = [], sequence = 0, browser;
const errors = [], checks = [], evidence = join(root, 'docs/evidence'), parent = join(root, '.runtime');
await mkdir(evidence, { recursive: true });
const temporary = await mkdtemp(join(parent, 'goals-browser-'));
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
    args: ['--disable-gpu', '--disable-gpu-shader-disk-cache'],
    env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1360, height: 1050 } });
  browser.setDefaultTimeout(15000);
  await browser.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-goals-token');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'goals-fixture', displayName: '目标与计划验收', skillTags: [], demo: false }));
  });
  await browser.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url());
    if (url.origin !== origin) return route.abort();
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    if (url.pathname === '/api/learning/goals') return ok([goal]);
    if (url.pathname === '/api/learning/observations') return ok({ observations: [{ id: 'b'.repeat(64), status: 'ACTIVE', executionMode: 'deepseek', competencyId: goal.competencyId, dimension: 'CAUSAL_EXPLANATION', sourceRound: 1, fact: { observation: 'NOT_OBSERVED' } }], preferences: { personalizationEnabled: true, revision: 1 } });
    if (url.pathname === '/api/questions') return ok({ items: goal.questionIds.map((id, i) => ({ id, title: `存储能力场景 ${i + 1}`, competencyId: goal.competencyId, topic: 'DATABASE', difficulty: 'STANDARD', version: 1, tags: [], questionSummary: '合成题目范围' })), page: 1, size: 100, total: goal.questionIds.length });
    if (url.pathname === '/api/interviews/training-tasks') return ok({ items: tasks, page: 1, size: 20, total: tasks.length });
    if (url.pathname.endsWith('/criteria')) {
      const body = req.postDataJSON(); assert.equal(body.confirmed, true); assert.equal(body.expectedRevision, goal.revision);
      goal.revision++; goal.criteriaRevision++; goal.requiredScenarios = body.requiredScenarios; goal.dimensions = body.dimensions;
      goal.completionCriterion = '在至少两个不同场景核对因果解释、方案权衡与职责；须本人逐场复核，不代表专业能力认证。';
      goal.plans.forEach(p => { p.status = 'SUPERSEDED'; });
      checks.push('criteria require explicit consent and invalidate old drafts'); return ok(goal);
    }
    if (url.pathname.endsWith('/complete')) {
      const body = req.postDataJSON(); assert.equal(body.confirmed, true); assert.equal(body.expectedRevision, goal.revision);
      assert.equal(body.evidenceHash, 'a'.repeat(64)); assert.ok(body.reviewNote.length > 5);
      goal.status = 'ACHIEVED'; goal.revision++;
      checks.push('completion binds consent, current revision, source digest and review note'); return ok(goal);
    }
    if (url.pathname.endsWith('/plan-commands')) {
      assert.deepEqual(req.postDataJSON().observationIds, ['b'.repeat(64)]);
      const previous = goal.plans[0];
      goal.plans.unshift({ ...structuredClone(previous), id: 'proposed-plan-' + ++sequence, revision: 1, status: 'PENDING_CONFIRMATION', taskIds: [], observationRefs: [{ id: 'b'.repeat(64), revision: 1, sourceSessionId: 'synthetic-history', sourceVersion: 'v1' }] });
      goal.reservedRuns++;
      checks.push('goal planning pins explicitly selected observation IDs and exposes source link');
      return ok({ id: 'synthetic-goal-run', status: 'ACCEPTED', error: null });
    }
    if (url.pathname === '/api/interview-runs/synthetic-goal-run') return ok({ runId: 'synthetic-goal-run', status: 'SUCCEEDED' });
    const plan = goal.plans.find(p => url.pathname.includes('/plans/' + p.id + '/'));
    if (plan) {
      const body = req.postDataJSON();
      assert.equal(body.expectedRevision, plan.revision);
      if (url.pathname.endsWith('/revisions')) { plan.revision++; plan.items = body.items; checks.push('edit creates a fresh revision'); }
      else if (url.pathname.endsWith('/confirm')) {
        assert.deepEqual(Object.keys(body).sort(), ['expectedRevision', 'requestId']);
        plan.status = 'APPLIED';
        plan.taskIds = plan.items.map((_, i) => 'confirmed-task-' + i);
        tasks = plan.items.map((item, i) => ({ id: plan.taskIds[i], goalId: goal.id, sourceSessionId: '', sourceRound: 0, questionId: item.questionId, title: item.title, gap: item.acceptance, status: 'TODO', activeSessionId: null, answeredCount: 0, totalRounds: 3, createdAt: new Date().toISOString() }));
        checks.push('confirmation contains only revision and request key');
      } else if (url.pathname.endsWith('/reject')) { plan.status = 'REJECTED'; checks.push('draft rejection produces no tasks'); }
      else throw new Error('Unexpected plan operation');
      return ok(plan);
    }
    if (url.pathname.endsWith('/review')) return ok({ outcome: 'AWAITING_HUMAN_REVIEW', validScenarios: 2, requiredScenarios: 2,
      evidenceHash: 'a'.repeat(64), goalRevision: goal.revision, criteriaRevision: goal.criteriaRevision, dimensions: goal.dimensions,
      results: [{ sessionId: 'synthetic-s1', limitation: '合成真实模式结构记录；不代表专业验收' }, { sessionId: 'synthetic-s2', limitation: '请核对原回答' }], limitation: '本人确认只记录训练目标完成，不替代独立专业验收。' });
    errors.push('Unexpected API: ' + url.pathname); return route.fulfill({ status: 404, body: '{}' });
  });
  const page = await browser.newPage(); page.on('pageerror', e => errors.push(e.message));
  await page.goto(origin + '/learning/goals');
  await page.getByRole('button', { name: '修改完成标准' }).click();
  assert.equal(await page.getByRole('button', { name: '确认并保存新标准' }).isEnabled(), false);
  await page.getByText('方案权衡', { exact: true }).click();
  await page.waitForFunction(() => document.querySelector('input[value="TRADEOFFS"]')?.checked);
  await page.getByRole('checkbox', { name: '我确认这些是本次训练的完成条件，结束时仍须逐场复核。' }).check();
  await page.getByRole('button', { name: '确认并保存新标准' }).click();
  await page.getByText('已被替代', { exact: true }).waitFor();
  await page.reload(); await page.getByText('已被替代', { exact: true }).waitFor();
  checks.push('reload preserves confirmed criteria and superseded draft');
  await page.getByRole('combobox', { name: `为${goal.title}选择学习观察` }).click();
  await page.getByText('因果解释 · 第 1 轮 · 尚未充分观察', { exact: true }).click();
  await page.getByRole('button', { name: '提出训练草稿' }).click();
  await page.getByRole('link', { name: '查看原场次 · 观察修订 1' }).waitFor();
  await page.getByRole('button', { name: '拒绝草稿' }).click();
  await page.getByText('已拒绝', { exact: true }).waitFor();
  await page.getByRole('button', { name: '核对跨场证据' }).click();
  await page.getByText('有效场景 2 / 2 · 等待本人核对').waitFor();
  const complete = page.getByRole('button', { name: '确认训练目标完成' });
  assert.equal(await complete.isEnabled(), false);
  await page.getByRole('textbox', { name: '逐场复核记录' }).fill('逐场检查了原回答、反例与限制；本次仅确认训练目标完成。');
  assert.equal(await complete.isEnabled(), false);
  await page.getByRole('checkbox', { name: '我已核对各场原回答及结论，确认本次训练目标完成；这不是独立专业认证。' }).check();
  await page.screenshot({ path: join(evidence, 'goal-completion-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForSelector('.ant-layout-sider-collapsed');
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider').getBoundingClientRect().width <= 65);
  assert.equal(await page.getByLabel('训练目标与确认计划', { exact: true }).evaluate(element => element.scrollWidth <= element.clientWidth), true);
  await page.getByText('有效场景 2 / 2 · 等待本人核对').evaluate(element => element.scrollIntoView({ block: 'start' }));
  await page.screenshot({ path: join(evidence, 'goal-completion-mobile.png'), fullPage: true });
  checks.push('390px viewport has no horizontal overflow');
  await complete.click(); await page.getByText('已达成', { exact: true }).waitFor();
  await page.reload(); await page.getByText('已达成', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '提出训练草稿' }).count(), 0);
  checks.push('confirmed state survives reload and stops new planning');
  assert.deepEqual(errors, []);
  await writeFile(join(evidence, 'goal-completion-browser.json'), JSON.stringify({ transport: 'intercepted synthetic fixture, not live API or semantic review', checks, errors, realProviderCalls: 0 }, null, 2) + '\n');
  console.log(JSON.stringify({ checks, errors }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(done => server.close(done));
  const actual = await realpath(temporary), allowed = await realpath(parent);
  if (!actual.startsWith(allowed + sep)) throw new Error('Refusing cleanup outside owned project directory');
  // Windows 上 Edge 关闭上下文后需要短暂时间释放 GPU 缓存文件。
  for (let attempt = 0; ; attempt++) {
    try { await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 }); break; }
    catch (error) { if (attempt === 4 || !['EPERM', 'EBUSY', 'ENOTEMPTY'].includes(error.code)) throw error; await new Promise(done => setTimeout(done, 1000)); }
  }
}
