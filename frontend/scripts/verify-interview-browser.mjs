// 生产界面使用本地合成 API 验收，不产生外部流量。
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
let context, session, page, pendingResumeList;
try {
  context = await chromium.launchPersistentContext(join(temporary, 'profile'), { ...browserLaunchOptions(), headless: true,
    args: ['--disable-gpu', '--disable-gpu-shader-disk-cache'],
    env: { ...process.env, TMP: temporary, TEMP: temporary }, viewport: { width: 1440, height: 1550 } });
  await context.addInitScript(() => {
    localStorage.setItem('token', 'synthetic-interview-test');
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'test', displayName: '面试验收', targetRole: 'Java', skillTags: [], demo: false }));
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, data, message: '' } });
    if (url.pathname === '/api/interview-runs' || url.pathname.endsWith('/runs')) return ok([]);
    if (url.pathname === '/api/knowledge/documents') return ok({ items: [], page: 1, size: 100, total: 0 });
    if (url.pathname === '/api/resumes') {
      await pendingResumeList;
      return ok([resume]);
    }
    if (url.pathname === '/api/resumes/7') return ok(resume);
    if (url.pathname === '/api/resumes/7/versions') return ok({ items: [], page: 1, size: 20, total: 0 });
    if (url.pathname === '/api/target-jobs') return ok({ items: [job], page: 1, size: 12, total: 1 });
    if (url.pathname === '/api/target-jobs/target-9') return ok(job);
    if (url.pathname === '/api/interviews/sessions' || url.pathname === '/api/interviews/start-commands') {
      if (request.method() === 'GET') return ok({ items: [], page: 1, size: 20, total: 0 });
      const command = request.postDataJSON(); const body = command.configuration || command; writes.push(body);
      session = { sessionId: `session-${writes.length}`, targetRole: body.targetRole, resumeId: body.resumeId, resumeRevision: 2,
        resumeSnapshot: resume.summary, jobId: null, jobSnapshot: body.jobDescription || job.jdText,
        mode: body.mode, difficulty: body.difficulty, totalRounds: body.totalRounds, answerLeaseUntil: null,
        state: 'WAITING_ANSWER', turnCount: 1, turns: [{ speaker: 'interviewer', content: '请结合项目说明个人行动和验证依据。' }] };
      if (command.configuration) return ok({ runId: 'run-' + writes.length, sessionId: session.sessionId, requestId: command.requestId, operation: 'START', status: 'SUCCEEDED', phase: 'COMMIT', errorCode: null });
      return ok(session);
    }
    if (url.pathname.endsWith('/answer-commands')) {
      const command = request.postDataJSON();
      session.turns.push({ speaker: 'user', content: command.answer, requestId: command.requestId },
        { speaker: 'interviewer', content: `第 ${session.turnCount + 1} 轮：如何验证这次改动？` });
      session.turnCount += 1;
      return ok({ runId: 'run-answer', sessionId: session.sessionId, requestId: command.requestId,
        operation: 'ANSWER', status: 'SUCCEEDED', phase: 'COMMIT', errorCode: null });
    }
    if (url.pathname.startsWith('/api/interviews/sessions/')) return ok(session);
    if (url.pathname.startsWith('/api/')) { errors.push(`Unexpected API: ${url.pathname}`); return route.fulfill({ status: 404, body: '{}' }); }
    return route.continue();
  });
  page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/');
  await page.waitForURL('**/interview');
  await page.getByRole('heading', { name: '面试准备', exact: true }).waitFor();
  assert.equal(await page.getByRole('menuitem').count(), 5);
  for (const label of ['模拟面试', '题目与资料', '求职材料', '简历', '目标岗位']) assert.equal(await page.getByRole('menuitem', { name: label }).count(), 1);
  checks.push('Root opens interview; navigation includes the read-only question bank.');
  await page.getByText('本场自定义 JD', { exact: true }).click();
  await page.locator('#custom-role').fill('Java 高级后端');
  const jd = '负责订单接口设计、事务一致性与故障恢复。熟悉 Java、MySQL 与 Redis。';
  await page.locator('#custom-jd').fill(jd);
  await page.getByText('专项训练', { exact: true }).click();
  await page.locator('#interview-rounds').press('ArrowDown');
  await page.locator('.ant-select-item-option-content').getByText('4 轮', { exact: true }).click();
  await page.getByText('已就绪 · 本场共 4 轮', { exact: true }).waitFor();
  await page.getByText('岗位 JD 面试', { exact: true }).click();
  await page.getByText('已就绪 · 本场共 6 轮', { exact: true }).waitFor();
  checks.push('Switching training formats preserves a valid length for each format.');
  await page.getByText('挑战', { exact: true }).click();
  assert.equal(await page.getByRole('radio', { name: /挑战/ }).isChecked(), true);
  await page.waitForFunction(() => document.querySelector('input[value="ADVANCED"]')?.closest('.ant-radio')?.classList.contains('ant-radio-checked')); 
  await page.waitForFunction(() => !document.querySelector('input[value="STANDARD"]')?.closest('.ant-radio')?.classList.contains('ant-radio-checked'));
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === '开始模拟面试' && !button.disabled));
  await page.screenshot({ path: join(evidence, 'interview-settings.png'), fullPage: true });
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=session-1');
  await page.getByText('挑战难度', { exact: false }).waitFor();
  assert.equal(writes[0].difficulty, 'ADVANCED');
  assert.equal(writes[0].totalRounds, 6);
  assert.equal(writes[0].jobDescription, jd);
  assert.equal(writes[0].resumeId, undefined);
  assert.equal(writes[0].targetJobId, undefined);
  await page.reload();
  await page.getByText('挑战难度', { exact: false }).waitFor();
  checks.push('JD-only interview does not require a resume; inline material and difficulty survive reopening.');

  await page.goto(origin + '/interview?resumeId=99&targetJobId=target-9');
  await page.getByRole('button', { name: '移除本场简历' }).waitFor();
  assert.equal(await page.getByRole('button', { name: '开始模拟面试', exact: true }).isDisabled(), true);
  await page.getByRole('button', { name: '移除本场简历' }).click();
  await page.getByText('已就绪 · 本场共 6 轮', { exact: true }).waitFor();
  let releaseResumes;
  pendingResumeList = new Promise(resolve => { releaseResumes = resolve; });
  await page.goto(origin + '/interview?resumeId=7&targetJobId=target-9');
  await page.getByText('正在读取已选简历…', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '开始模拟面试', exact: true }).isDisabled(), true);
  releaseResumes();
  checks.push('A linked resume must load or be explicitly removed before starting.');
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).waitFor({ state: 'visible' });
  await page.getByText('入门', { exact: true }).click();
  await page.getByRole('button', { name: '开始模拟面试', exact: true }).click();
  await page.waitForURL('**/interview?sessionId=session-2');
  assert.equal(writes[1].difficulty, 'BEGINNER');
  assert.equal(writes[1].targetJobId, 'target-9');
  assert.equal(writes[1].resumeId, 7);
  assert.equal(writes[1].targetJobRevision, 3);
  assert.equal(writes[1].jobDescription, undefined);
  checks.push('Saved JD selection sends its revision and excludes inline material.');

  const answerInput = page.getByLabel('面试回答', { exact: true });
  await answerInput.fill('确认候选词');
  const confirmed = await answerInput.evaluate(input => {
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '词' }));
    const key = { key: 'Enter', bubbles: true, cancelable: true, isComposing: false };
    const keyAccepted = input.dispatchEvent(new KeyboardEvent('keydown', key));
    const editAccepted = input.dispatchEvent(new InputEvent('beforeinput', {
      bubbles: true, cancelable: true, inputType: 'insertFromComposition', data: '词',
    }));
    input.dispatchEvent(new KeyboardEvent('keyup', key));
    return keyAccepted && editAccepted;
  });
  assert.equal(confirmed, true);
  await answerInput.press('Shift+Enter');
  assert.equal(await answerInput.inputValue(), '确认候选词\n');
  await page.keyboard.insertText('补充回答');
  const [submitted] = await Promise.all([
    page.waitForRequest(request => request.url().endsWith('/answer-commands')),
    answerInput.press('Enter'),
  ]);
  assert.equal(submitted.postDataJSON().answer, '确认候选词\n补充回答');
  await page.getByText('第 2 轮：如何验证这次改动？', { exact: true }).waitFor();
  await answerInput.fill('继续说明验证结果');
  await answerInput.press('Control+Enter');
  await page.getByText('第 3 轮：如何验证这次改动？', { exact: true }).waitFor();
  checks.push('输入法确认候选词后可继续编辑，Shift+Enter 换行，Enter 提交完整回答。');

  await page.goto(origin + '/resumes');
  await page.getByRole('button', { name: /预览/ }).click();
  await page.getByRole('button', { name: /浏览器打印/ }).waitFor();
  await page.emulateMedia({ media: 'print' });
  assert.equal(await page.locator('[data-resume-preview]').isVisible(), true);
  assert.equal(await page.locator('#root').isVisible(), false);
  const printBounds = await page.locator('[data-resume-preview]').boundingBox();
  assert.ok(printBounds.x < 2 && printBounds.width > 1000, 'Print layout must fill the page rather than shrink to a centered modal');
  await page.screenshot({ path: join(evidence, 'resume-print.png'), fullPage: true });
  await page.emulateMedia({ media: 'screen' });
  await page.getByRole('button', { name: '关闭预览', exact: true }).click();
  await page.getByRole('button', { name: /编辑/ }).click();
  await page.waitForURL('**/resumes/edit?resumeId=7');
  await page.getByRole('button', { name: 'AI 优化简历', exact: true }).waitFor();
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
  await page.screenshot({ path: join(evidence, 'interview-mobile.png'), fullPage: true });
  checks.push('Retired URLs return to interview; mobile form stays within viewport.');
  assert.deepEqual(errors, []);
  await writeFile(join(evidence, 'interview-browser.json'), JSON.stringify({ checks, requests: writes, errors }, null, 2));
  console.log(JSON.stringify({ checks, errors }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ error: String(error), errors, text: await page?.locator('body').innerText() }));
  throw error;
} finally {
  await context?.close();
  await new Promise(done => server.close(done));
  const actual = await realpath(temporary), parent = await realpath(join(root, '.runtime'));
  if (!actual.startsWith(parent + sep)) throw new Error('Refusing cleanup outside owned browser directory');
  await rm(actual, { recursive: true, force: false, maxRetries: 4, retryDelay: 250 });
}
