// Run against a local production preview. Every API uses synthetic data; no model/database is contacted.
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const output = new URL('../../tmp/ai-review-browser/', import.meta.url);
await mkdir(output, { recursive: true });
const origin = process.env.AI_REVIEW_TEST_URL || 'http://127.0.0.1:4175';
const profile = await mkdtemp(fileURLToPath(new URL('profile-', output)));
const context = await chromium.launchPersistentContext(profile, {
  channel: process.env.AI_REVIEW_BROWSER_CHANNEL || 'msedge', headless: true,
  downloadsPath: fileURLToPath(new URL('downloads/', output)), viewport: { width: 1280, height: 960 },
});
const results = [], errors = [], writes = [];
let pendingOptimize, pendingSave, failure = false, optimizeCalls = 0, page;
const hold = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { release, promise }; };
const original = '开发接口。完成测试。';
const fixture = id => ({ id, revision: 1, ownerName: `验收用户${id}`, targetRole: 'Java 后端', summary: original,
  skills: ['Java'], educationExperiences: [], projectExperiences: [], internshipExperiences: [], certificates: [], templateKey: 'classic',
  details: { projects: [{ name: '项目甲', contributions: ['开发甲接口', '完成甲测试'] },
    { name: '项目乙', contributions: ['开发乙接口', '完成乙测试'] }] } });
const saved = new Map([[1, fixture(1)], [2, fixture(2)]]);
try {
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-ai-review-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'review-test', displayName: 'AI 验收', targetRole: 'Java 后端', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    if (url.pathname === '/api/ai/resume/optimize') {
      optimizeCalls++;
      const input = request.postDataJSON(), gate = pendingOptimize;
      pendingOptimize = undefined;
      if (gate) await gate.promise;
      if (failure) return route.fulfill({ status: 502, json: { success: false, message: '模型暂时不可用', data: null } });
      return ok({ optimizedText: input.rawText.replace('开发', '设计').replace('完成', '补充'),
        suggestions: ['核对实际贡献'], improvements: [], targetKeywords: [], optimizationSummary: '让动作更清晰' });
    }
    if (/^\/api\/resumes\/\d+$/.test(url.pathname)) {
      const id = Number(url.pathname.split('/').pop());
      if (request.method() === 'PUT') {
        const data = request.postDataJSON(); writes.push(data);
        assert.equal(data.revision, saved.get(id).revision);
        saved.set(id, { ...data, id, revision: data.revision + 1 });
        const gate = pendingSave; pendingSave = undefined;
        if (gate) await gate.promise;
      }
      return ok(saved.get(id));
    }
    if (url.pathname === '/api/resumes') {
      if (request.method() === 'POST') {
        const data = request.postDataJSON(), gate = pendingSave;
        pendingSave = undefined; saved.set(3, { ...data, id: 3, revision: 0 });
        if (gate) await gate.promise;
        return ok(saved.get(3));
      }
      return ok([...saved.values()]);
    }
    if (url.pathname.startsWith('/api/')) return ok([]);
    return route.continue();
  });
  page = await context.newPage();
  page.setDefaultTimeout(10_000);
  page.on('pageerror', error => errors.push(error.message));
  const summary = page.getByRole('region', { name: '个人简介 AI 优化', exact: true });
  const input = page.getByLabel('个人简介', { exact: true });
  const read = async () => input.inputValue();
  const open = async () => {
    await page.goto(`${origin}/resumes/edit?resumeId=1`);
    await input.waitFor();
  };
  const generate = async region => {
    await region.getByRole('button', { name: /AI 优化|基于当前内容重新生成/ }).click();
    await region.getByRole('checkbox', { name: '选择修改 1', exact: true }).waitFor();
    await region.getByRole('button', { name: '基于当前内容重新生成', exact: true }).waitFor();
  };
  const adoptFirst = async region => {
    await region.getByRole('checkbox', { name: '选择修改 1', exact: true }).check();
    await region.getByRole('button', { name: '采用所选（1）', exact: true }).click();
  };

  await open(); await generate(summary);
  assert.equal(await read(), original); assert.equal(writes.length, 0);
  await summary.getByRole('button', { name: '拒绝其余建议' }).click();
  assert.equal(await read(), original);
  results.push('生成及拒绝不改原文、不提交保存');

  await generate(summary); await adoptFirst(summary);
  assert.equal(await read(), '设计接口。完成测试。');
  await input.fill((await read()) + '刚输入的文字。');
  await summary.getByRole('button', { name: '撤销上次采用' }).click();
  assert.equal(await read(), original + '刚输入的文字。');
  results.push('部分采用与撤销保留后续输入');

  await open(); const gate = hold(); pendingOptimize = gate;
  const requestArrived = page.waitForRequest('**/api/ai/resume/optimize');
  await summary.getByRole('button', { name: 'AI 优化个人简介', exact: true }).click(); await requestArrived;
  await input.fill(original + '模型等待期间输入。'); gate.release();
  await summary.getByText('文字或目标岗位已修改，旧建议不能采用。请基于当前内容重新生成；你的输入已保留。').waitFor();
  await summary.getByRole('checkbox', { name: '选择修改 1', exact: true }).check();
  assert.equal(await summary.getByRole('button', { name: '采用所选（1）', exact: true }).isDisabled(), true);
  assert.equal(await read(), original + '模型等待期间输入。');
  await generate(summary); await adoptFirst(summary);
  assert.equal(await read(), '设计接口。完成测试。模型等待期间输入。');
  results.push('延迟响应不覆盖输入，重新生成可继续采用');

  await input.fill('我重写了采用的片段。完成测试。模型等待期间输入。');
  await summary.getByRole('button', { name: '撤销上次采用' }).click();
  await summary.getByText(/无法安全自动撤销/).waitFor();
  assert.equal(await read(), '我重写了采用的片段。完成测试。模型等待期间输入。');
  results.push('重叠编辑阻止破坏性撤销');

  await open(); await generate(summary); await adoptFirst(summary);
  const saveGate = hold(); pendingSave = saveGate;
  const saving = page.waitForRequest(request => request.method() === 'PUT');
  await page.getByRole('button', { name: /保存$/ }).click(); await saving;
  await input.fill((await read()) + '保存等待期间输入。'); saveGate.release();
  await page.getByText('已保存提交时的版本；后续编辑仍在页面中，请再次保存。', { exact: true }).waitFor();
  assert.equal(await read(), '设计接口。完成测试。保存等待期间输入。');
  await summary.getByRole('button', { name: '撤销上次采用' }).click();
  assert.equal(await read(), original + '保存等待期间输入。');
  const nextSaved = page.waitForResponse(response => response.request().method() === 'PUT');
  await page.getByRole('button', { name: /保存$/ }).click(); await nextSaved;
  assert.equal(writes[1].revision, 2);
  await open(); assert.equal(await read(), original + '保存等待期间输入。');
  results.push('保存期间保留输入和撤销记录，再保存携带新 revision，刷新恢复已保存文字');

  const projectGate = hold(); pendingOptimize = projectGate;
  const second = page.getByRole('region', { name: '项目 2 贡献 AI 优化', exact: true });
  const projectRequest = page.waitForRequest('**/api/ai/resume/optimize');
  await second.getByRole('button', { name: 'AI 优化项目 2 贡献', exact: true }).click(); await projectRequest;
  await page.getByRole('button', { name: '移除项目', exact: true }).first().click();
  await page.getByRole('button', { name: /确 定|确定|Yes|OK/ }).last().click();
  projectGate.release();
  const shifted = page.getByRole('region', { name: '项目 1 贡献 AI 优化', exact: true });
  await shifted.getByRole('checkbox', { name: '选择修改 1', exact: true }).waitFor(); await adoptFirst(shifted);
  const contribution = page.getByLabel('个人贡献（每行一条）', { exact: true });
  assert.equal(await contribution.inputValue(), '设计乙接口\n完成乙测试');
  await shifted.getByRole('button', { name: '撤销上次采用' }).click();
  assert.equal(await contribution.inputValue(), '开发乙接口\n完成乙测试');
  assert.equal(await read(), original + '保存等待期间输入。');
  results.push('项目删除后序号变化仍定位原项目，部分采用和撤销不影响简介');

  const deletedGate = hold(); pendingOptimize = deletedGate;
  const deleting = page.waitForRequest('**/api/ai/resume/optimize');
  await shifted.getByRole('button', { name: '基于当前内容重新生成', exact: true }).click(); await deleting;
  await page.getByRole('button', { name: '移除项目', exact: true }).click();
  await page.getByRole('button', { name: /确 定|确定|Yes|OK/ }).last().click(); deletedGate.release();
  assert.equal(await page.getByLabel('个人贡献（每行一条）', { exact: true }).count(), 0);
  results.push('移除待优化项目使迟到响应失效');

  const switchGate = hold(); pendingOptimize = switchGate;
  const switching = page.waitForRequest('**/api/ai/resume/optimize');
  await summary.getByRole('button', { name: 'AI 优化个人简介', exact: true }).click(); await switching;
  await page.evaluate(() => { history.pushState({}, '', '/resumes/edit?resumeId=2'); dispatchEvent(new PopStateEvent('popstate')); });
  await page.getByLabel('姓名', { exact: true }).filter({ visible: true }).waitFor();
  await page.waitForFunction(() => document.getElementById('ownerName')?.value === '验收用户2');
  switchGate.release(); assert.equal(await read(), original);
  assert.equal(await summary.getByRole('checkbox').count(), 0);
  results.push('同页切换简历隔离旧请求与建议');

  failure = true;
  await summary.getByRole('button', { name: 'AI 优化个人简介', exact: true }).click();
  await summary.getByText(/模型暂时不可用/).waitFor();
  assert.equal(await read(), original); failure = false; await generate(summary);
  results.push('模型失败保留原文并允许重试');

  await summary.scrollIntoViewIfNeeded();
  await summary.screenshot({ path: fileURLToPath(new URL('desktop.png', output)) });
  await page.setViewportSize({ width: 390, height: 844 });
  await summary.scrollIntoViewIfNeeded();
  assert.equal(await summary.evaluate(element => element.scrollWidth <= element.clientWidth), true);
  const check = summary.getByRole('checkbox', { name: '选择修改 1', exact: true });
  await check.focus(); await page.keyboard.press('Space'); assert.equal(await check.isChecked(), true);
  await summary.getByRole('button', { name: '采用所选（1）', exact: true }).click();
  assert.equal(await read(), '设计接口。完成测试。');
  await summary.screenshot({ path: fileURLToPath(new URL('mobile.png', output)) });
  results.push('手机宽度对比区域无横向溢出，键盘可选择并采用建议');

  await page.setViewportSize({ width: 1280, height: 960 });
  await page.goto(`${origin}/resumes/edit`);
  await page.getByLabel('姓名', { exact: true }).fill('新建验收');
  await page.getByLabel('目标岗位', { exact: true }).fill('Java 后端');
  await input.fill(original); await generate(summary); await adoptFirst(summary);
  const createGate = hold(); pendingSave = createGate;
  const creating = page.waitForRequest(request => request.url().endsWith('/api/resumes') && request.method() === 'POST');
  await page.getByRole('button', { name: /保存$/ }).click(); await creating;
  await input.fill((await read()) + '首次保存时继续输入。'); createGate.release();
  await page.waitForURL('**/resumes/edit?resumeId=3');
  assert.equal(await read(), '设计接口。完成测试。首次保存时继续输入。');
  await summary.getByRole('button', { name: '撤销上次采用' }).click();
  assert.equal(await read(), original + '首次保存时继续输入。');
  results.push('首次创建后的编辑页保留新输入与撤销记录');
  assert.deepEqual(errors, []);
  await writeFile(new URL('verification.json', output), JSON.stringify({ results, optimizeCalls, saves: writes.length, pageErrors: errors }, null, 2));
  console.log(JSON.stringify({ results, optimizeCalls, saves: writes.length, pageErrors: errors }));
} catch (error) {
  if (page) {
    await page.screenshot({ path: fileURLToPath(new URL('failure.png', output)), fullPage: true });
    await writeFile(new URL('failure.txt', output), await page.locator('body').innerText());
  }
  throw error;
} finally { await context.close(); }
