import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createOptimizationController } from './optimizationController.ts';
import { applyTextEdits, buildTextEdits, undoText } from './textReview.ts';
import { resetAccountScope } from '../../api/accountScope.ts';

const controllers = [];
afterEach(() => { controllers.splice(0).forEach(controller => controller.dispose()); resetAccountScope(); });
const response = text => ({ optimizedText: text, suggestions: ['核对事实'], improvements: [], targetKeywords: [], optimizationSummary: '完善表达' });
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
};
function setup({ text = '开发接口。完成测试。', targetRole = 'Java 后端', optimize = async () => response('设计接口。补充测试。'), validate } = {}) {
  let value = text, role = targetRole, writes = 0;
  const controller = createOptimizationController({ read: () => value, write: text => { value = text; writes++; },
    targetRole: () => role, optimize, validate, changed: () => {} });
  controllers.push(controller);
  return { controller, read: () => value, writes: () => writes, edit: text => { value = text; }, role: text => { role = text; } };
}
function selectAll(controller) { controller.getSnapshot().review.edits.forEach(edit => controller.select(edit.id, true)); }

test('generation never writes; reject all leaves the original untouched', async () => {
  const f = setup(); await f.controller.generate();
  assert.equal(f.writes(), 0); assert.equal(f.controller.getSnapshot().review.edits.length, 2);
  selectAll(f.controller); f.controller.reject(); f.controller.apply();
  assert.equal(f.writes(), 0);
  assert.ok(f.controller.getSnapshot().review.edits.every(edit => edit.status === 'rejected'));
});

test('accept only selected suggestions, reject others, then undo the adoption', async () => {
  const f = setup(); await f.controller.generate();
  f.controller.select(0, true); f.controller.apply();
  assert.equal(f.read(), '设计接口。完成测试。');
  f.controller.reject([1]); f.controller.undo();
  assert.equal(f.read(), '开发接口。完成测试。');
  assert.equal(f.controller.getSnapshot().review.edits[1].status, 'rejected');
  assert.equal(f.controller.getSnapshot().undoCount, 0);
});

test('sequential adoptions can each be undone without discarding a later appended sentence', async () => {
  const f = setup(); await f.controller.generate();
  f.controller.select(0, true); f.controller.apply(); f.controller.select(1, true); f.controller.apply();
  f.edit(f.read() + '刚输入的内容。');
  f.controller.undo(); assert.equal(f.read(), '设计接口。完成测试。刚输入的内容。');
  f.controller.undo(); assert.equal(f.read(), '开发接口。完成测试。刚输入的内容。');
});

test('typing before a late response keeps new input and blocks stale adoption', async () => {
  const result = deferred(); const f = setup({ optimize: () => result.promise });
  const task = f.controller.generate(); f.edit('用户刚输入的文字。');
  result.resolve(response('模型旧建议。')); await task;
  selectAll(f.controller); f.controller.apply();
  assert.equal(f.writes(), 0); assert.equal(f.read(), '用户刚输入的文字。');
  assert.match(f.controller.getSnapshot().error, /已保留/);
});

test('typing after review and changing target role are both checked again on apply', async () => {
  for (const change of [f => f.edit('新文字'), f => f.role('Go 后端')]) {
    const f = setup(); await f.controller.generate(); selectAll(f.controller); change(f); f.controller.apply();
    assert.equal(f.writes(), 0); assert.match(f.controller.getSnapshot().error, /重新生成/);
  }
});

test('overlapping manual edit prevents destructive undo and keeps the undo record', async () => {
  const f = setup(); await f.controller.generate(); selectAll(f.controller); f.controller.apply();
  f.edit('我重写了这段。补充测试。'); f.controller.undo();
  assert.equal(f.read(), '我重写了这段。补充测试。');
  assert.equal(f.controller.getSnapshot().undoCount, 1); assert.match(f.controller.getSnapshot().error, /手动调整/);
});

test('cancel and a superseding request fence late successes and errors', async () => {
  const requests = [], signals = [];
  const f = setup({ optimize: (_, signal) => { const result = deferred(); requests.push(result); signals.push(signal); return result.promise; } });
  const first = f.controller.generate(); f.controller.reject();
  assert.equal(signals[0].aborted, true); requests[0].resolve(response('过期内容')); await first;
  assert.equal(f.controller.getSnapshot().review, null);
  const second = f.controller.generate(); const third = f.controller.generate();
  requests[2].resolve(response('最新建议')); await third; requests[1].reject(new Error('旧错误')); await second;
  assert.equal(signals[1].aborted, true); assert.equal(f.controller.getSnapshot().review.response.optimizedText, '最新建议');
  assert.equal(f.controller.getSnapshot().error, '');
});

test('field removal, route unmount and account reset prevent late response or undo effects', async () => {
  for (const invalidate of [controller => controller.dispose(), () => resetAccountScope()]) {
    const result = deferred(); let signal;
    const f = setup({ optimize: (_, value) => { signal = value; return result.promise; } });
    const task = f.controller.generate(); invalidate(f.controller);
    result.resolve(response('其他简历的内容')); await task;
    f.controller.apply(); f.controller.undo();
    assert.equal(signal.aborted, true); assert.equal(f.writes(), 0); assert.equal(f.controller.getSnapshot().review, null);
  }
});

test('a refreshed proposal uses the new draft, and undo survives another generation', async () => {
  let calls = 0;
  const f = setup({ optimize: async data => { calls++; return response(calls === 1 ? '设计接口。完成测试。' : data.rawText + '新增建议。'); } });
  await f.controller.generate(); selectAll(f.controller); f.controller.apply();
  f.edit(f.read() + '补充事实。'); await f.controller.generate();
  assert.equal(f.controller.getSnapshot().review.source, '设计接口。完成测试。补充事实。');
  f.controller.undo(); assert.equal(f.read(), '开发接口。完成测试。补充事实。');
  assert.equal(f.controller.getSnapshot().review, null);
});

test('project fields stay isolated and preserve unselected lines', async () => {
  const project = setup({ text: '开发接口\n完成测试', optimize: async () => response('设计接口\n补充测试') });
  const summary = setup();
  await project.controller.generate(); project.controller.select(0, true); project.controller.apply();
  assert.equal(project.read(), '设计接口\n完成测试'); assert.equal(summary.writes(), 0);
  project.controller.undo(); assert.equal(project.read(), '开发接口\n完成测试');
});

test('invalid or failed output never writes and supports retry', async () => {
  for (const invalid of [null, '', 'x'.repeat(12001), new Error('请求超时')]) {
    let retry = false;
    const f = setup({ optimize: async () => {
      if (retry) return response('可重试的建议');
      if (invalid instanceof Error) throw invalid;
      return response(invalid);
    } });
    await f.controller.generate(); assert.equal(f.writes(), 0); assert.ok(f.controller.getSnapshot().error);
    assert.equal(f.controller.getSnapshot().loading, false);
    retry = true; await f.controller.generate(); assert.equal(f.controller.getSnapshot().error, '');
  }
});

test('input and destination limits do not truncate user data', async () => {
  for (const input of [{ text: ' ' }, { text: 'x'.repeat(8001) }, { targetRole: 'x'.repeat(121) }]) {
    let calls = 0; const f = setup({ ...input, optimize: async () => { calls++; return response('建议'); } });
    await f.controller.generate(); assert.equal(calls, 0); assert.equal(f.writes(), 0);
  }
  const f = setup({ validate: () => '贡献超出长度限制' });
  await f.controller.generate(); selectAll(f.controller); f.controller.apply();
  assert.equal(f.writes(), 0); assert.match(f.controller.getSnapshot().error, /长度/);
});

test('unchanged output has no selectable edits or undo entry', async () => {
  const f = setup({ optimize: async data => response(data.rawText) });
  await f.controller.generate(); f.controller.apply();
  assert.equal(f.controller.getSnapshot().review.edits.length, 0); assert.equal(f.writes(), 0);
});

test('diff round trips multilingual, repeated, inserted, deleted and bounded long input exactly', () => {
  const samples = ['', '原文', '甲。乙。甲。', '新增\n甲。\n乙！', '😀开头。\n保留空格  \n', '。!?\n', '行\n'.repeat(201)];
  for (const before of samples) for (const after of samples) {
    const edits = buildTextEdits(before, after);
    assert.equal(applyTextEdits(before, edits), after);
    assert.equal(applyTextEdits(before, []), before);
    assert.equal(undoText(before, after, after), before);
  }
});

test('undo preserves external insertion/deletion and rejects edits inside the adopted span', () => {
  assert.equal(undoText('前原文后', '前建议后', '新增前建议后'), '新增前原文后');
  assert.equal(undoText('前原文后', '前建议后', '建议后'), '原文后');
  assert.equal(undoText('前原文后', '前建议后', '前建我的字议后'), null);
  assert.equal(undoText('前原文后', '前后', '前新输入后'), null);
});
