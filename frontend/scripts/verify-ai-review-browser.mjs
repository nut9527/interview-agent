// 使用合成简历验证完整的编辑、审阅与保存流程，仅允许访问本地预览。
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import { loadChromium, browserLaunchOptions } from './browser-runtime.mjs';
const chromium = await loadChromium();
const output = new URL('../../tmp/ai-review-browser/', import.meta.url);
await mkdir(output, { recursive: true });
const origin = process.env.AI_REVIEW_TEST_URL || 'http://127.0.0.1:4175';
const profile = await mkdtemp(fileURLToPath(new URL('profile-', output)));
const context = await chromium.launchPersistentContext(profile, {
  ...browserLaunchOptions(process.env, process.env.AI_REVIEW_BROWSER_CHANNEL), headless: true,
  downloadsPath: fileURLToPath(new URL('downloads/', output)), viewport: { width: 1280, height: 960 },
});
const fixture = id => ({ id, revision: 1, ownerName: '验收用户' + id, targetRole: '后端工程师', summary: '开发借阅工具。',
  skills: ['Java'], educationExperiences: [], projectExperiences: [], internshipExperiences: ['完成接口联调。'],
  certificates: [], templateKey: 'classic', details: { contact: { email: 'private@example.org' },
    projects: [{ name: '预约平台', background: '提供预约服务。', contributions: ['开发预约接口。'] }] } });
const saved = new Map([[1, fixture(1)], [2, fixture(2)]]);
const requests = [], writes = [], errors = [];
let responseGate, failure = false, page;
try {
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-ai-review-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'review-test', displayName: '验收用户', skillTags: [] }));
  });
  await context.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    if (url.pathname === '/api/ai/resume/optimize-draft') {
      const body = req.postDataJSON();
      requests.push(body);
      if (responseGate) await responseGate;
      if (failure) return route.fulfill({ status: 502, json: { success: false, message: '模型暂时不可用', data: null } });
      return ok({ changes: body.entries.flatMap((entry, index) => {
        const text = entry.text.replace('开发', '实现');
        return text === entry.text ? [] : [{ index, text }];
      }) });
    }
    if (/^\/api\/resumes\/\d+$/.test(url.pathname)) {
      const id = Number(url.pathname.split('/').pop());
      if (req.method() === 'PUT') {
        const data = req.postDataJSON();
        writes.push(data);
        assert.equal(data.revision, saved.get(id).revision);
        saved.set(id, { ...data, id, revision: data.revision + 1 });
      }
      return ok(saved.get(id));
    }
    if (url.pathname === '/api/resumes') return ok([...saved.values()]);
    if (url.pathname.startsWith('/api/')) return ok([]);
    return route.continue();
  });
  page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.setDefaultTimeout(12000);
  const open = page.getByRole('button', { name: 'AI 优化简历', exact: true });
  const summary = page.getByLabel('个人简介', { exact: true });
  const dialog = page.getByRole('dialog', { name: '简历优化建议', exact: true });
  const selectSummary = dialog.getByRole('checkbox', { name: '采用：个人简介', exact: true });
  const close = async () => { await dialog.getByRole('button', { name: /^关\s*闭$/ }).last().click(); await dialog.waitFor({ state: 'hidden' }); };
  await page.goto(origin + '/resumes/edit?resumeId=1');
  await page.getByText('当前内容已与服务器同步', { exact: true }).waitFor();
  assert.equal(await open.count(), 1);
  assert.equal(await page.getByRole('button', { name: /AI 优化个人简介|AI 优化项目/ }).count(), 0);
  const original = '开发借阅工具，增加到期提醒。';
  await summary.fill(original);
  await open.click(); await selectSummary.waitFor();
  assert.equal(requests.length, 1);
  assert.ok(requests[0].entries.some(e => e.kind === 'summary' && e.text === original));
  assert.ok(requests[0].entries.some(e => e.kind === 'projectContribution' && e.text === '开发预约接口。'));
  assert.ok(requests[0].entries.some(e => e.kind === 'internshipExperience'));
  assert.doesNotMatch(JSON.stringify(requests[0]), /private@example.org|验收用户/);
  assert.equal(await selectSummary.isChecked(), false);
  await selectSummary.check();
  const apply = dialog.getByRole('button', { name: /采用所选修改/ });
  await apply.click(); await close();
  assert.equal(await summary.inputValue(), original.replace('开发','实现'));
  assert.equal(writes.length, 0);
  await open.click();
  await dialog.getByRole('button', { name: '撤销上次采用' }).click();
  await close();
  assert.equal(await summary.inputValue(), original);
  await open.click(); await selectSummary.check(); await apply.click(); await close();
  await page.getByRole('button', { name: /^保\s*存$/ }).click();
  await page.getByText('当前内容已与服务器同步', { exact: true }).waitFor();
  assert.equal(writes.length, 1);
  assert.deepEqual(writes[0].details.projects[0].contributions, ['开发预约接口。']);
  assert.equal(writes[0].summary, original.replace('开发','实现'));

  // 取消慢请求后切换简历，迟到的响应不能带入下一份草稿。
  await page.reload();
  await page.getByText('当前内容已与服务器同步', { exact: true }).waitFor();
  let release;
  responseGate = new Promise(resolve => { release = resolve; });
  const pending = page.waitForRequest('**/api/ai/resume/optimize-draft');
  await open.click();
  await pending;
  await dialog.getByRole('button', { name: '取消生成', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  await page.goto(origin + '/resumes/edit?resumeId=2');
  release(); responseGate = undefined;
  await page.getByText('当前内容已与服务器同步', { exact: true }).waitFor();
  assert.equal(await summary.inputValue(), saved.get(2).summary);
  failure = true; await open.click();
  await dialog.getByText('模型暂时不可用', { exact: true }).waitFor();
  assert.equal(writes.length, 1);
  failure = false;
  await dialog.getByRole('button', { name: '重新生成', exact: true }).click();
  await selectSummary.waitFor();
  await dialog.screenshot({ path: fileURLToPath(new URL('desktop.png', output)) });
  await page.setViewportSize({ width: 390, height: 844 });
  await selectSummary.focus(); await page.keyboard.press('Space');
  assert.equal(await selectSummary.isChecked(), true);
  assert.ok(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
  await dialog.screenshot({ path: fileURLToPath(new URL('mobile.png', output)) });
  assert.deepEqual(errors, []);
  console.log('统一入口、多段单次审阅、选择采用、撤销、保存、取消、重试与手机布局通过。');
} catch (error) {
  if (page) {
    await page.screenshot({ path: fileURLToPath(new URL('failure.png', output)), fullPage: true });
    await writeFile(new URL('failure.txt', output), await page.locator('body').innerText());
  }
  throw error;
} finally { await context.close(); }
