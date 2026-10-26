// Controlled browser acceptance against a local preview; synthetic API responses only.
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const output = new URL('../../tmp/resume-editor-browser/', import.meta.url);
await mkdir(output, { recursive: true });
const origin = process.env.RESUME_EDITOR_TEST_URL || 'http://127.0.0.1:4177';
const profileDir = await mkdtemp(fileURLToPath(new URL('profile-', output)));
const context = await chromium.launchPersistentContext(profileDir, { channel: 'msedge', headless: true,
  downloadsPath: fileURLToPath(new URL('downloads/', output)), viewport: { width: 1280, height: 960 } });
const hold = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { release, promise }; };
const fixture = id => ({ id, revision: 1, ownerName: `验收用户${id}`, targetRole: 'Java 后端', summary: '开发接口。完成测试。',
  skills: ['Java'], educationExperiences: [], projectExperiences: [], internshipExperiences: [], certificates: [], templateKey: 'classic',
  details: { projects: [] } });
const saved = new Map([[1, fixture(1)], [2, fixture(2)]]);
const writes = [], results = [], errors = [];
let nextId = 10, parseCalls = 0, parseGate, saveGate, getGate, failSave = false, page;
try {
  await context.addInitScript(() => {
    if (!localStorage.getItem('token')) {
      localStorage.setItem('token', 'synthetic-editor-test');
      localStorage.setItem('user', JSON.stringify({ id: 1, username: 'editor-test', displayName: '编辑验收', targetRole: 'Java', skillTags: [], demo: false }));
    }
  });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    const ok = data => route.fulfill({ json: { success: true, message: '', data } });
    if (url.pathname === '/api/resumes/import/parse') {
      parseCalls++; const gate = parseGate; parseGate = undefined; if (gate) await gate.promise;
      return ok({ ...fixture(0), id: undefined, ownerName: '待核对姓名', targetRole: '' });
    }
    if (url.pathname === '/api/ai/resume/optimize') {
      const data = request.postDataJSON();
      return ok({ optimizedText: data.rawText.replace('开发', '设计'), suggestions: [], improvements: [], targetKeywords: [], optimizationSummary: '措辞修改' });
    }
    if (/^\/api\/resumes\/\d+\/versions$/.test(url.pathname)) return ok({ items: [], total: 0, page: 1, size: 20 });
    if (/^\/api\/resumes\/\d+$/.test(url.pathname)) {
      const id = Number(url.pathname.split('/').pop());
      if (request.method() === 'PUT') {
        const data = request.postDataJSON(); writes.push({ method: 'PUT', id, ...data });
        if (failSave) return route.fulfill({ status: 503, json: { success: false, message: '暂时无法保存', data: null } });
        if (data.revision !== saved.get(id).revision) return route.fulfill({ status: 409, json: { success: false, message: '修改冲突', data: null } });
        saved.set(id, { ...data, id, revision: data.revision + 1 });
        const snapshot = saved.get(id), gate = saveGate; saveGate = undefined; if (gate) await gate.promise;
        return ok(snapshot);
      }
      if (request.method() === 'DELETE') { saved.delete(id); return ok(null); }
      const snapshot = request.headers().authorization === 'Bearer synthetic-other'
        ? { ...fixture(id), ownerName: '乙的简历', summary: '账号乙自己的简介', revision: 7 } : saved.get(id), gate = getGate; getGate = undefined; if (gate) await gate.promise;
      return ok(snapshot);
    }
    if (url.pathname === '/api/resumes') {
      if (request.method() === 'POST') {
        const data = request.postDataJSON(), id = nextId++;
        writes.push({ method: 'POST', id, ...data }); saved.set(id, { ...data, id, revision: 0 });
        const gate = saveGate; saveGate = undefined; if (gate) await gate.promise;
        return ok(saved.get(id));
      }
      return ok([...saved.values()]);
    }
    if (url.pathname.startsWith('/api/')) return ok([]);
    return route.continue();
  });
  page = await context.newPage(); page.setDefaultTimeout(12000);
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  const summary = page.getByLabel('个人简介', { exact: true });
  const save = page.getByRole('button', { name: /保\s*存$/ });
  const waitText = text => page.getByText(text, { exact: true }).waitFor();
  const open = async id => {
    await page.goto(`${origin}/resumes/edit?resumeId=${id}`);
    await page.getByText('当前内容已与服务器同步', { exact: true }).waitFor();
    assert.equal(await summary.inputValue(), saved.get(id).summary);
  };
  const draft = () => page.evaluate(() => Object.keys(sessionStorage).filter(key => key.startsWith('zhizhida:resume-draft:')).map(key => JSON.parse(sessionStorage.getItem(key))));
  const saveAndWait = async () => {
    const response = page.waitForResponse(r => r.request().method() === 'PUT');
    await save.click(); await response; await waitText('当前内容已与服务器同步');
  };
  await open(1); await summary.fill('刷新前未保存\n');
  assert.equal(await page.evaluate(() => { const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); return event.defaultPrevented; }), true);
  await page.reload();
  await page.getByRole('button', { name: '恢复草稿', exact: true }).click();
  assert.equal(await summary.inputValue(), '刷新前未保存\n');
  await page.getByRole('button', { name: /返\s*回$/ }).click();
  await waitText('还有未保存的修改'); await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  assert.match(page.url(), /resumeId=1/);
  await saveAndWait(); assert.equal((await draft()).length, 0);
  results.push('刷新恢复原始换行；离开可取消；保存成功清理草稿');

  await summary.fill('准备提交'); const gate = hold(); saveGate = gate;
  const saving = page.waitForRequest(r => r.method() === 'PUT'); await save.click(); await saving;
  await waitText('正在保存提交时的内容…'); await summary.fill('保存期间新输入'); gate.release();
  await waitText('有未保存的修改');
  assert.equal(await summary.inputValue(), '保存期间新输入');
  assert.equal((await draft())[0].revision, saved.get(1).revision);
  await saveAndWait(); results.push('保存期间保留输入，下一次保存使用新修订号');

  await summary.fill('断网输入'); failSave = true; await save.click();
  await page.getByText(/暂时无法保存。输入已保留/).waitFor();
  assert.equal((await draft())[0].values.summary, '断网输入'); failSave = false; await saveAndWait();
  results.push('保存失败保留输入并显示持续错误，手动重试成功');

  await summary.fill('本地简介'); saved.set(1, { ...saved.get(1), revision: saved.get(1).revision + 1, summary: '服务器简介', targetRole: '服务器岗位' });
  await save.click(); const conflict = page.getByRole('dialog', { name: /修改冲突/ }); await conflict.waitFor();
  const accept = conflict.getByRole('button', { name: '采用选择，返回编辑' });
  assert.equal(await accept.isDisabled(), true);
  const summaryField = conflict.locator('section').filter({ has: page.getByRole('heading', { name: '个人简介', exact: true }) });
  await summaryField.getByRole('radio', { name: '保留我的内容' }).check(); await accept.click();
  assert.equal(await summary.inputValue(), '本地简介'); assert.equal(await page.getByLabel('目标岗位', { exact: true }).inputValue(), '服务器岗位');
  // Another write after reviewing must conflict again instead of force-overwriting.
  saved.set(1, { ...saved.get(1), revision: saved.get(1).revision + 1, summary: '再次修改' });
  await save.click(); await conflict.waitFor();
  await conflict.locator('section').filter({ has: page.getByRole('heading', { name: '个人简介', exact: true }) }).getByRole('radio', { name: '保留我的内容' }).check();
  const comparisonText = conflict.getByText('本地简介', { exact: true });
  assert.equal(await comparisonText.evaluate(el => parseFloat(getComputedStyle(el).fontSize) >= 14 && el.getBoundingClientRect().height > 0), true);
  await conflict.getByText('再次修改', { exact: true }).waitFor();
  await conflict.screenshot({ path: fileURLToPath(new URL('conflict-desktop.png', output)) });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await conflict.evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await conflict.screenshot({ path: fileURLToPath(new URL('conflict-mobile.png', output)) });
  await accept.click(); await page.setViewportSize({ width: 1280, height: 960 }); await saveAndWait();
  results.push('冲突逐项选择、保留另一方独立修改；二次冲突继续校验 revision；手机对比无溢出');

  await summary.fill('离开后保留的草稿'); await page.getByRole('link', { name: '简历管理', exact: true }).click();
  await page.getByRole('button', { name: '保留草稿并离开' }).click(); await page.waitForURL('**/resumes');
  await page.goto(`${origin}/resumes/edit?resumeId=1`); await page.getByRole('button', { name: '丢弃草稿', exact: true }).click();
  await page.getByRole('button', { name: /确 定|确定|Yes|OK/ }).last().click();
  assert.equal(await summary.inputValue(), saved.get(1).summary); assert.equal((await draft()).length, 0);
  results.push('站内跳转保留草稿，再进入可明确丢弃');

  await page.goto(`${origin}/resumes`); await page.getByRole('button', { name: /导入简历$/ }).click();
  const beforeImport = writes.length;
  await page.locator('input[type=file]').setInputFiles({ name: 'review.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic') });
  await page.waitForURL('**/resumes/edit?import=1'); await page.getByLabel('姓名', { exact: true }).fill('纠正姓名');
  await page.getByRole('button', { name: '确认并保存导入' }).click();
  await waitText('请输入目标岗位'); assert.equal(writes.length, beforeImport);
  await page.getByLabel('目标岗位', { exact: true }).fill('纠正岗位');
  await page.reload(); await page.getByRole('button', { name: '恢复草稿', exact: true }).click();
  assert.equal(await page.getByLabel('姓名', { exact: true }).inputValue(), '纠正姓名');
  await page.getByRole('button', { name: '确认并保存导入' }).click(); await page.waitForURL('**/resumes/edit?resumeId=10');
  await waitText('当前内容已与服务器同步');
  assert.equal(writes.length, beforeImport + 1); assert.equal(saved.get(10).targetRole, '纠正岗位');
  assert.equal((await draft()).length, 0); results.push('导入先解析，必填检查，纠正草稿刷新恢复，确认后仅创建一次');

  await page.getByRole('button', { name: /返\s*回$/ }).click(); await page.waitForURL('**/resumes');
  await page.getByRole('button', { name: /新建简历$/ }).first().click();
  await page.getByLabel('姓名', { exact: true }).fill('新建用户'); await page.getByLabel('目标岗位', { exact: true }).fill('Java');
  await summary.fill('首次保存'); const createGate = hold(); saveGate = createGate;
  const creating = page.waitForRequest(r => r.url().endsWith('/api/resumes') && r.method() === 'POST');
  await save.click(); await creating; await summary.fill('首次保存时新输入'); createGate.release();
  await page.waitForURL('**/resumes/edit?resumeId=11'); await waitText('有未保存的修改');
  assert.equal(await summary.inputValue(), '首次保存时新输入'); assert.equal((await draft())[0].key, 11);
  await saveAndWait();
  // A same-component transition back to a new form must not keep id=11.
  await page.evaluate(() => { window.history.pushState({}, '', '/resumes/edit'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.getByRole('heading', { name: /新建简历$/ }).waitFor();
  assert.equal(await page.getByLabel('姓名', { exact: true }).inputValue(), '');
  results.push('首次创建迁移草稿并保留新输入，再进入新建不会误编辑已创建记录');

  await open(2); const optimizer = page.getByRole('region', { name: '个人简介 AI 优化', exact: true });
  await optimizer.getByRole('button', { name: 'AI 优化个人简介' }).click();
  await optimizer.getByRole('checkbox', { name: '选择修改 1', exact: true }).check();
  await optimizer.getByRole('button', { name: '采用所选（1）' }).click();
  await waitText('有未保存的修改'); assert.match((await draft())[0].values.summary, /设计/);
  await optimizer.getByRole('button', { name: '撤销上次采用' }).click(); await waitText('当前内容已与服务器同步');
  results.push('AI 程序化采用/撤销也同步草稿与脏状态');

  await summary.fill('退出前的私有草稿'); await page.getByRole('button', { name: /退出登录$/ }).click();
  await page.getByText('未保存的简历修改将被清理', { exact: true }).last().waitFor(); await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  assert.equal(await summary.inputValue(), '退出前的私有草稿');
  const accountGate = hold(); saveGate = accountGate;
  const oldSave = page.waitForRequest(r => r.method() === 'PUT'); await save.click(); await oldSave;
  await page.evaluate(() => {
    localStorage.setItem('token', 'synthetic-other'); localStorage.setItem('user', JSON.stringify({ id: 2, username: 'other', displayName: '账号乙', skillTags: [] }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'token', storageArea: localStorage }));
  });
  accountGate.release(); await page.getByText('账号乙', { exact: true }).waitFor();
  await waitText('当前内容已与服务器同步'); assert.equal((await draft()).length, 0);
  assert.equal(await summary.inputValue(), '账号乙自己的简介');
  results.push('退出登录先提示；账号切换清理草稿，旧保存响应不能回写');

  await page.goto(`${origin}/resumes`); await page.getByRole('button', { name: /导入简历$/ }).click();
  const lateParse = hold(); parseGate = lateParse;
  const parsing = page.waitForRequest('**/api/resumes/import/parse');
  await page.locator('input[type=file]').setInputFiles({ name: 'late.pdf', mimeType: 'application/pdf', buffer: Buffer.from('synthetic') }); await parsing;
  await page.getByRole('dialog', { name: /导入简历$/ }).getByRole('button', { name: /取\s*消$/ }).click();
  lateParse.release();
  await page.getByRole('button', { name: /导入简历$/ }).click();
  assert.equal((await draft()).length, 0); assert.match(page.url(), /\/resumes$/);
  results.push('取消解析后迟到响应不生成草稿或跳转');
  await page.getByRole('dialog', { name: /导入简历$/ }).getByRole('button', { name: /取\s*消$/ }).click();
  await page.goto(`${origin}/resumes/edit?resumeId=2`); await waitText('当前内容已与服务器同步');
  await summary.fill('退出时清理');
  await page.getByRole('button', { name: /退出登录$/ }).click();
  await page.getByRole('button', { name: '清理草稿并退出', exact: true }).click();
  await page.waitForURL('**/login'); assert.equal((await draft()).length, 0);
  assert.equal(await page.evaluate(() => localStorage.getItem('token')), null);
  results.push('确认退出清理草稿和身份，用户为空时编辑器安全卸载');
  assert.deepEqual(errors, []);
  const evidence = { results, parseCalls, writes: writes.length, pageErrors: errors };
  await writeFile(new URL('verification.json', output), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));
} catch (error) {
  if (page) { await page.screenshot({ path: fileURLToPath(new URL('failure.png', output)), fullPage: true });
    await writeFile(new URL('failure.txt', output), await page.locator('body').innerText()); }
  throw error;
} finally { await context.close(); }
