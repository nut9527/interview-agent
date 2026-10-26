// Production UI acceptance with synthetic local API data and no external traffic.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, extname, join, sep } from 'node:path';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = join(root, 'frontend/dist');
const evidence = join(root, 'docs/evidence');
await mkdir(evidence, { recursive: true });
await mkdir(join(root, '.runtime'), { recursive: true });
const temporary = await mkdtemp(join(root, '.runtime/interview-browser-'));
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
const resume = { id: 7, revision: 2, ownerName: '面试验收用户', targetRole: 'Java 后端', summary: '开发接口并完成对照测试。',
  skills: ['Java', 'MySQL'], educationExperiences: [], projectExperiences: ['负责订单接口设计'],
  internshipExperiences: [], certificates: [], templateKey: 'classic', details: { projects: [] } };
const job = { id: 'target-9', revision: 3, title: 'Java 实习生', company: '示例公司', jdText: '熟悉 Java 和 MySQL，负责接口开发。',
  sourceName: '', sourceUrl: '', deadline: null, requirements: [], updatedAt: '2026-10-26T00:00:00Z' };
const writes = [], errors = [], checks = [];
let context, session, page;
try {
  context = await chromium.launchPersistentContext(join(temporary, 'profile'), { channel: 'msedge', headless: true,
    env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1440, height: 1550 } });
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-interview-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'test', displayName: '面试验收', targetRole: 'Java', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, data, message: '' } });
    if (url.pathname === '/api/resumes') return ok([resume]);
    if (url.pathname === '/api/resumes/7') return ok(resume);
    if (url.pathname === '/api/resumes/7/versions') return ok({ items: [], page: 1, size: 20, total: 0 });
    if (url.pathname === '/api/target-jobs') return ok({ items: [job], page: 1, size: 12, total: 1 });
    if (url.pathname === '/api/target-jobs/target-9') return ok(job);
    if (url.pathname === '/api/interviews/sessions') {
      if (request.method() === 'GET') return ok({ items: [], page: 1, size: 20, total: 0 });
      const body = request.postDataJSON(); writes.push(body);
      session = { sessionId: `session-${writes.length}`, targetRole: body.targetRole, resumeId: body.resumeId, resumeRevision: 2,
        resumeSnapshot: resume.summary, jobId: null, jobSnapshot: body.jobDescription || job.jdText,
        mode: body.mode, difficulty: body.difficulty, totalRounds: body.totalRounds, answerLeaseUntil: null,
        state: 'WAITING_ANSWER', turnCount: 1, turns: [{ speaker: 'interviewer', content: '请结合项目说明个人行动和验证依据。' }] };
      return ok(session);
    }
    if (url.pathname.startsWith('/api/interviews/sessions/')) return ok(session);
    if (url.pathname.startsWith('/api/')) { errors.push(`Unexpected API: ${url.pathname}`); return route.fulfill({ status: 404, body: '{}' }); }
    return route.continue();
  });
  page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/');
  await page.waitForURL('**/interview');
  await page.getByRole('heading', { name: '面试准备', exact: true }).waitFor();
  assert.equal(await page.getByRole('menuitem').count(), 4);
  for (const label of ['面试训练室', '简历管理', '目标岗位', '账号设置']) assert.equal(await page.getByRole('menuitem', { name: label }).count(), 1);
  checks.push('Root opens interview; navigation has only four retained modules.');
  await page.getByText('本场自定义 JD', { exact: true }).click();
  await page.locator('#custom-role').fill('Java 高级后端');
  const jd = '负责订单接口设计、事务一致性与故障恢复。熟悉 Java、MySQL 与 Redis。';
  await page.locator('#custom-jd').fill(jd);
  await page.getByText('挑战', { exact: true }).click();
  assert.equal(await page.getByRole('radio', { name: /挑战/ }).isChecked(), true);
  await page.waitForFunction(() => document.querySelector('input[value="ADVANCED"]')?.closest('.ant-radio')?.classList.contains('ant-radio-checked')); 
  await page.waitForFunction(() => !document.querySelector('input[value="STANDARD"]')?.closest('.ant-radio')?.classList.contains('ant-radio-checked'));
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === '开始模拟面试' && !button.disabled));
  await page.screenshot({ path: join(evidence, '2026-10-26-interview-settings.png'), fullPage: true });
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=session-1');
  await page.getByText('挑战难度', { exact: false }).waitFor();
  assert.equal(writes[0].difficulty, 'ADVANCED');
  assert.equal(writes[0].jobDescription, jd);
  assert.equal(writes[0].resumeId, 7);
  assert.equal(writes[0].targetJobId, undefined);
  await page.reload();
  await page.getByText('挑战难度', { exact: false }).waitFor();
  checks.push('Inline JD and difficulty submit correctly and survive reopening a session.');

  await page.goto(origin + '/interview?resumeId=7&targetJobId=target-9');
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).waitFor({ state: 'visible' });
  await page.getByText('入门', { exact: true }).click();
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=session-2');
  assert.equal(writes[1].difficulty, 'BEGINNER');
  assert.equal(writes[1].targetJobId, 'target-9');
  assert.equal(writes[1].targetJobRevision, 3);
  assert.equal(writes[1].jobDescription, undefined);
  checks.push('Saved JD selection sends its revision and excludes inline material.');

  await page.goto(origin + '/resumes');
  await page.getByRole('button', { name: /预览/ }).click();
  await page.getByRole('button', { name: /浏览器打印/ }).waitFor();
  await page.emulateMedia({ media: 'print' });
  assert.equal(await page.locator('[data-resume-preview]').isVisible(), true);
  assert.equal(await page.locator('#root').isVisible(), false);
  const printBounds = await page.locator('[data-resume-preview]').boundingBox();
  assert.ok(printBounds.x < 2 && printBounds.width > 1000, 'Print layout must fill the page rather than shrink to a centered modal');
  await page.screenshot({ path: join(evidence, '2026-10-26-resume-print.png'), fullPage: true });
  await page.emulateMedia({ media: 'screen' });
  await page.getByRole('button', { name: '关闭预览', exact: true }).click();
  await page.getByRole('button', { name: /编辑/ }).click();
  await page.waitForURL('**/resumes/edit?resumeId=7');
  await page.getByRole('button', { name: 'AI 优化个人简介', exact: true }).waitFor();
  await page.getByRole('button', { name: /版本历史/ }).click();
  await page.getByText('暂无版本历史', { exact: false }).waitFor();
  checks.push('Resume list, preview, browser print, editor, AI optimization and version history remain accessible.');

  for (const path of ['/home', '/jobs', '/job-match', '/applications', '/career-report']) {
    await page.goto(origin + path); await page.waitForURL('**/interview');
  }
  await page.setViewportSize({ width: 390, height: 2100 });
  await page.goto(origin + '/interview?resumeId=7');
  await page.getByText('本场自定义 JD', { exact: true }).click();
  await page.locator('#custom-role').fill('移动端验收');
  await page.locator('#custom-jd').fill(jd);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
  await page.screenshot({ path: join(evidence, '2026-10-26-interview-mobile.png'), fullPage: true });
  checks.push('Retired URLs return to interview; mobile form stays within viewport.');
  assert.deepEqual(errors, []);
  await writeFile(join(evidence, '2026-10-26-interview-browser.json'), JSON.stringify({ checks, requests: writes, errors }, null, 2));
  console.log(JSON.stringify({ checks, errors }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ errors, text: await page?.locator('body').innerText() }));
  throw error;
} finally {
  await context?.close();
  await new Promise(done => server.close(done));
  const actual = await realpath(temporary), parent = await realpath(join(root, '.runtime'));
  if (!actual.startsWith(parent + sep)) throw new Error('Refusing cleanup outside owned browser directory');
  await rm(actual, { recursive: true, force: false });
}
