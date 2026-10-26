// Local production UI with controlled synthetic API responses. No external network.
// Run after npm run build; PLAYWRIGHT_MODULE can name an existing installation.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, extname, join, sep } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = join(root, 'frontend', 'dist');
await mkdir(join(root, 'tmp'), { recursive: true });
const output = await mkdtemp(join(root, 'tmp', 'target-jobs-browser-'));
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ttf': 'font/ttf' };
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const path = resolve(dist, '.' + (extname(pathname) ? pathname : '/index.html'));
    if (!path.startsWith(dist + sep)) { res.writeHead(403).end(); return; }
    res.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream'); res.end(await readFile(path));
  } catch { res.writeHead(404).end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
let context, page;
const results = [], errors = [], writes = [];
const jd = '任职要求：\n熟悉 Java 和 MySQL\n加分项：\n熟悉 Docker\n工作地点待定';
const requirements = [
  { id: 'r1', text: '熟悉 Java 和 MySQL', kind: 'MUST', keywords: ['java', 'mysql'], sourceText: '熟悉 Java 和 MySQL' },
  { id: 'r2', text: '熟悉 Docker', kind: 'BONUS', keywords: ['docker'], sourceText: '熟悉 Docker' },
  { id: 'r3', text: '工作地点待定', kind: 'UNKNOWN', keywords: [], sourceText: '工作地点待定' },
];
let saved, conflict = false, failExtract = false, nextAnalysisGate;
const hold = () => { let release; const promise = new Promise(done => { release = done; }); return { promise, release }; };
try {
  context = await chromium.launchPersistentContext(join(output, 'profile'), { channel: 'msedge', headless: true,
    env: { ...process.env, TEMP: output, TMP: output }, viewport: { width: 1280, height: 960 } });
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-target-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'target-test', displayName: '目标岗位验收', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    const fail = (status, message) => route.fulfill({ status, json: { success: false, message, data: null } });
    if (request.headers().authorization === 'Bearer synthetic-other' && url.pathname.startsWith('/api/')) {
      return ok(url.pathname === '/api/target-jobs' ? { items: [], total: 0, page: 1, size: 12 } : []);
    }
    if (url.pathname === '/api/resumes') return ok([1, 2].map(id => ({ id, ownerName: `简历${id}`, targetRole: 'Java 后端', revision: id,
      skills: [], educationExperiences: [], projectExperiences: [], internshipExperiences: [], certificates: [] })));
    if (url.pathname === '/api/target-jobs/extract') {
      if (failExtract) return fail(503, '暂时无法提取');
      return ok({ requirements, warnings: ['请核对分类和检索词'], ruleVersion: 'target-jd-v1' });
    }
    if (url.pathname === '/api/target-jobs') {
      if (request.method() === 'POST') { const input = request.postDataJSON(); writes.push(input); saved = { ...input, id: 'target-1', updatedAt: new Date().toISOString() }; return ok(saved); }
      return ok({ items: saved ? [saved] : [], total: saved ? 1 : 0, page: 1, size: 12 });
    }
    if (url.pathname.endsWith('/analysis')) {
      const { resumeId } = request.postDataJSON(); const gate = nextAnalysisGate; nextAnalysisGate = undefined;
      const result = { targetJobId: saved.id, targetJobRevision: saved.revision, resumeId, resumeRevision: resumeId, ruleVersion: 'target-jd-v1',
        requirements: saved.requirements.map((requirement, i) => ({ requirement, status: i === 0 ? 'REFERENCED' : 'UNKNOWN', unreferencedKeywords: i === 0 ? ['mysql'] : requirement.keywords,
          evidence: i === 0 ? [{ fieldPath: 'details.projects[0].contributions[0]', label: '项目贡献', quote: `简历${resumeId}：使用 Java 实现接口`, keywords: ['java'] }] : [] })),
        unknownInformation: ['截止日期之外的条件仍需确认'] };
      if (gate) await gate.promise;
      return ok(result);
    }
    if (url.pathname === '/api/target-jobs/target-1') {
      if (request.method() === 'PUT') {
        if (conflict) return fail(409, '目标岗位已变更，请重新打开最新版后核对修改。');
        const input = request.postDataJSON(); writes.push(input); saved = { ...saved, ...input, revision: saved.revision + 1 }; return ok(saved);
      }
      if (request.method() === 'DELETE') { saved = undefined; return ok(null); }
      return ok(saved);
    }
    if (url.pathname.startsWith('/api/')) return ok([]);
    return route.continue();
  });
  page = await context.newPage(); page.setDefaultTimeout(12000);
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/target-jobs');
  await page.getByRole('button', { name: /添加目标岗位$/ }).click();
  const editor = () => page.getByRole('dialog', { name: /添加我的目标岗位|编辑目标岗位/ });
  await editor().getByLabel('岗位名称', { exact: true }).fill('Java 后端实习生');
  await editor().getByLabel('公司', { exact: true }).fill('示例研发团队');
  await editor().getByLabel('来源', { exact: true }).fill('公司官网');
  await editor().getByLabel('来源链接', { exact: true }).fill('javascript:alert(1)');
  await editor().getByLabel('投递截止日期').fill('2026-11-01');
  await editor().getByLabel('粘贴完整 JD').fill(jd);
  assert.equal(await editor().getByRole('button', { name: '保存目标岗位' }).isDisabled(), true);
  failExtract = true; await editor().getByRole('button', { name: '从 JD 提取要求' }).click();
  await editor().getByText('暂时无法提取', { exact: true }).waitFor();
  assert.equal(await editor().getByLabel('粘贴完整 JD').inputValue(), jd);
  failExtract = false; await editor().getByRole('button', { name: '从 JD 提取要求' }).click();
  await editor().getByText('请核对分类和检索词', { exact: true }).waitFor();
  await editor().getByRole('button', { name: '保存目标岗位' }).click();
  await editor().getByText('请输入完整的 HTTP/HTTPS 链接，不能包含凭据', { exact: true }).waitFor();
  assert.equal(writes.length, 0);
  await editor().getByLabel('来源链接', { exact: true }).fill('https://example.test/job/1');
  await editor().getByLabel('要求内容', { exact: true }).nth(1).fill('Docker 部署实践（已核对）');
  await editor().getByLabel('要求 2 · 分类', { exact: true }).focus();
  await editor().getByLabel('要求 2 · 分类', { exact: true }).press('ArrowDown');
  await page.getByTitle('必须要求', { exact: true }).last().click();
  await editor().getByRole('button', { name: /手动补充要求$/ }).click();
  await editor().getByLabel('要求内容', { exact: true }).last().fill('每周到岗三天，待确认');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => { const rect = document.querySelector('.ant-modal-content').getBoundingClientRect(); return rect.left >= -1 && rect.right <= window.innerWidth + 1; });
  assert.equal(await editor().evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await editor().getByLabel('岗位名称', { exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, 'editor-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 960 });
  await editor().getByRole('button', { name: '保存目标岗位' }).click();
  await page.getByText('目标岗位已保存', { exact: true }).waitFor();
  assert.equal(saved.requirements.length, 4); assert.equal(saved.requirements[1].kind, 'MUST'); assert.equal(saved.deadline, '2026-11-01');
  results.push('粘贴 JD、失败重试、URL 校验、纠正分类和文本、手动补充及保存 metadata');
  results.push('手机编辑表单保持单列，弹窗和正文不超出视口');
  await page.reload(); await page.getByRole('button', { name: '查看要求与简历证据 →' }).click();
  await page.getByText('Docker 部署实践（已核对）', { exact: true }).waitFor();
  const choose = async name => { await page.getByLabel('用哪份简历核对').focus(); await page.getByLabel('用哪份简历核对').press('ArrowDown'); await page.getByTitle(name, { exact: true }).click(); };
  await choose('简历1 · Java 后端');
  await page.getByText('简历1：使用 Java 实现接口', { exact: true }).waitFor();
  await page.locator('blockquote[data-field-path="details.projects[0].contributions[0]"]').waitFor();
  assert.equal(await page.getByText('未知 · 暂无证据', { exact: true }).count(), 3);
  results.push('刷新读回纠正后的要求，按简历展示原文路径和未知项');
  const gate = hold(); nextAnalysisGate = gate;
  await page.locator('.ant-drawer-title').click();
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('button')].find(el => el.textContent.trim() === '重新核对');
    return button && !button.classList.contains('ant-btn-loading');
  });
  await Promise.all([page.waitForRequest(r => r.url().endsWith('/analysis')), page.getByRole('button', { name: '重新核对', exact: true }).click()]);
  await choose('简历2 · Java 后端'); await page.getByText('简历2：使用 Java 实现接口', { exact: true }).waitFor();
  gate.release(); await page.waitForTimeout(150);
  assert.equal(await page.getByText('简历1：使用 Java 实现接口', { exact: true }).count(), 0);
  results.push('切换简历中止旧分析，迟到结果不回填');
  await page.locator('.ant-drawer-title').click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').waitFor({ state: 'hidden' });
  await page.screenshot({ path: join(output, 'desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => { const rect = document.querySelector('.ant-drawer-open .ant-drawer-content-wrapper')?.getBoundingClientRect(); return rect && rect.left >= -1 && rect.width <= window.innerWidth + 1; });
  const drawer = page.locator('.ant-drawer-body');
  assert.equal(await drawer.evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await page.screenshot({ path: join(output, 'mobile.png'), fullPage: true });
  results.push('桌面和 390px 手机分析页面无横向溢出');
  await page.setViewportSize({ width: 1280, height: 960 });
  await page.getByRole('button', { name: '编辑 / 纠正要求', exact: true }).click();
  await editor().getByLabel('公司', { exact: true }).fill('我的未保存修改'); conflict = true;
  await editor().getByRole('button', { name: '保存目标岗位' }).click();
  await editor().getByText('目标岗位已变更，请重新打开最新版后核对修改。', { exact: true }).waitFor();
  assert.equal(await editor().getByLabel('公司', { exact: true }).inputValue(), '我的未保存修改');
  conflict = false;
  await editor().getByLabel('粘贴完整 JD').fill(jd + '\n补充沟通要求');
  assert.equal(await editor().getByRole('button', { name: '保存目标岗位' }).isDisabled(), true);
  await editor().getByRole('button', { name: '从 JD 提取要求' }).click();
  await page.getByRole('button', { name: '保留当前要求', exact: true }).click();
  assert.equal(await editor().getByLabel('要求内容', { exact: true }).nth(1).inputValue(), 'Docker 部署实践（已核对）');
  await editor().getByRole('button', { name: /取\s*消$/ }).click();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  assert.equal(await editor().isVisible(), true);
  await editor().getByRole('button', { name: /取\s*消$/ }).click(); await page.getByRole('button', { name: '放弃修改', exact: true }).click();
  results.push('409 保留输入；JD 改动需重提取；取消重提取和取消关闭保留纠正');
  await page.getByRole('dialog', { name: 'Java 后端实习生', exact: true }).getByRole('button', { name: /^删\s*除$/ }).click();
  await page.getByRole('dialog', { name: /删除/ }).getByRole('button', { name: /取\s*消$/ }).click();
  await page.getByRole('dialog', { name: /删除/ }).waitFor({ state: 'hidden' });
  assert.ok(saved);
  await page.getByRole('dialog', { name: 'Java 后端实习生', exact: true }).getByRole('button', { name: /^删\s*除$/ }).click();
  await page.getByRole('dialog', { name: /删除/ }).getByRole('button', { name: /^删\s*除$/ }).click();
  await page.getByText('把想投的第一份 JD 放在这里', { exact: true }).waitFor();
  assert.equal(saved, undefined); results.push('删除必须确认，取消不删除，确认后列表刷新');
  saved = { ...writes[0], id: 'target-1', updatedAt: new Date().toISOString() };
  await page.reload(); await page.getByRole('button', { name: '查看要求与简历证据 →' }).click();
  const accountGate = hold(); nextAnalysisGate = accountGate;
  await Promise.all([page.waitForRequest(r => r.url().endsWith('/analysis')), choose('简历1 · Java 后端')]);
  await page.evaluate(() => {
    localStorage.setItem('token', 'synthetic-other');
    localStorage.setItem('user', JSON.stringify({ id: 2, username: 'other', displayName: '账号乙', skillTags: [] }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'token', storageArea: localStorage }));
  });
  await page.getByText('账号乙', { exact: true }).waitFor();
  await page.getByText('把想投的第一份 JD 放在这里', { exact: true }).waitFor();
  accountGate.release(); await page.waitForTimeout(150);
  assert.equal(await page.getByText('简历1：使用 Java 实现接口', { exact: true }).count(), 0);
  assert.equal(await page.getByText('Java 后端实习生', { exact: true }).count(), 0);
  results.push('账号切换清空岗位和分析视图，旧账号迟到结果不能写回');
  assert.deepEqual(errors, []);
  const report = { results, writes: writes.length, pageErrors: errors, output };
  await writeFile(join(output, 'verification.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
} catch (error) {
  if (page) { await page.screenshot({ path: join(output, 'failure.png'), fullPage: true }); await writeFile(join(output, 'failure.txt'), await page.locator('body').innerText()); }
  console.error('Browser artifacts:', output); throw error;
} finally { await context?.close(); await new Promise(done => server.close(done)); }
