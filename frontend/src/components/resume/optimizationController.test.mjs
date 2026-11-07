import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createOptimizationController } from './optimizationController.ts';
import { applyTextEdits, buildTextEdits, undoText } from './textReview.ts';
import { formValues } from '../../pages/Resumes/Editor/editorData.ts';
import { resetAccountScope } from '../../api/accountScope.ts';

const controllers = [];
afterEach(() => { controllers.splice(0).forEach(controller => controller.dispose()); resetAccountScope(); });

const base = () => formValues({ ownerName: '不发送的姓名', targetRole: '测试岗', summary: '参与测试',
  skills: ['编写用例'], educationExperiences: ['测试大学'], projectExperiences: ['旧项目', '', '旧记录'],
  internshipExperiences: ['维护工单'], certificates: ['示例证书'],
  details: { contact: { email: 'private@example.org' }, projects: [
    { name: '预约台', role: '开发', background: '预约平台', contributions: ['增加提醒', '编写文档'], techStack: ['Java'] },
  ] },
});
const edits = { changes: [{ index: 0, text: '参与测试。' }, { index: 3, text: '旧记录。' }, { index: 5, text: '增加提醒。' }] };
function setup(optimize = async () => edits, initial = base()) {
  let value = initial, writes = 0;
  const controller = createOptimizationController({ read: () => value, write: next => { value = next; writes++; }, optimize, changed: () => {} });
  controllers.push(controller);
  return { controller, read: () => value, writes: () => writes };
}
const deferred = () => {
  let resolve;
  const promise = new Promise(ok => { resolve = ok; });
  return { promise, resolve };
};

test('one request groups draft prose without contact details and keeps blank legacy lines in place', async () => {
  let calls = 0;
  const f = setup(async input => {
    calls++;
    assert.deepEqual(input.entries.map(e => e.kind), ['summary', 'skill', 'projectExperience', 'projectExperience',
      'projectBackground', 'projectContribution', 'projectContribution', 'internshipExperience']);
    assert.equal(input.entries[5].projectName, '预约台');
    assert.equal(input.targetRole, '测试岗');
    assert.doesNotMatch(JSON.stringify(input), /private|不发送的姓名|示例证书|测试大学/);
    return edits;
  });
  await f.controller.generate();
  assert.equal(calls, 1);
  assert.equal(f.writes(), 0);
  assert.ok(f.controller.getSnapshot().edits.every(e => !e.selected));
  f.controller.select(3, true); f.controller.select(5, true); f.controller.apply();
  assert.equal(f.read().projectExperiences, '旧项目\n\n旧记录。');
  assert.deepEqual(f.read().details.projects[0].contributions, ['增加提醒。','编写文档']);
  assert.equal(f.read().summary, '参与测试');
  f.read().details.contact.email = 'manual@example.org';
  f.controller.undo();
  assert.equal(f.read().details.contact.email, 'manual@example.org');
  assert.equal(f.read().projectExperiences, base().projectExperiences);
  assert.deepEqual(f.read().details.projects, base().details.projects);
});

test('stale draft or moved projects block adoption, including changes while the model runs', async () => {
  for (const edit of [v => {v.summary = '改写';}, v => {v.targetRole = '新岗位';}, v => {v.details.projects[0].name = '另一个项目';}]) {
    const task = deferred(), f = setup(() => task.promise);
    const generating = f.controller.generate(); edit(f.read()); task.resolve(edits); await generating;
    f.controller.select(0, true); f.controller.apply();
    assert.ok(f.controller.stale());
    assert.equal(f.writes(), 0);
    assert.match(f.controller.getSnapshot().error, /重新生成/);
  }
});

test('applying a later selection and undoing it leaves the first selection adopted', async () => {
  const f = setup(); await f.controller.generate();
  f.controller.select(0, true); f.controller.apply();
  f.controller.select(5, true); f.controller.apply(); f.controller.undo();
  assert.equal(f.read().summary, '参与测试。');
  assert.deepEqual(f.read().details.projects, base().details.projects);
  assert.equal(f.controller.getSnapshot().edits.find(e => e.index === 0).accepted, true);
  assert.equal(f.controller.getSnapshot().edits.find(e => e.index === 5).accepted, false);
});

test('batch undo is atomic and never overwrites a later edit', async () => {
  const f = setup(); await f.controller.generate();
  f.controller.select(0, true); f.controller.select(5, true); f.controller.apply();
  f.read().details.projects[0].contributions[0] = '手动修改';
  f.controller.undo();
  assert.equal(f.writes(), 1);
  assert.equal(f.read().summary, '参与测试。');
  assert.equal(f.read().details.projects[0].contributions[0], '手动修改');
  assert.equal(f.controller.getSnapshot().canUndo, true);
});

test('undoing a summary preserves appended words; regeneration explicitly uses the new draft', async () => {
  const sent = [];
  const f = setup(async input => { sent.push(input); return edits; }); await f.controller.generate();
  f.controller.select(0,true); f.controller.apply(); f.read().summary += '后续输入';
  await f.controller.generate(); f.controller.undo();
  assert.equal(sent.at(-1).entries[0].text, '参与测试。后续输入');
  assert.equal(f.read().summary, '参与测试后续输入');
  assert.equal(f.controller.getSnapshot().edits, null);
});

test('cancellation, account reset and unmount discard late model responses', async () => {
  for (const stop of [c => c.cancel(), c => c.dispose(), () => resetAccountScope()]) {
    const task = deferred(); let signal;
    const f = setup((_, s) => {signal = s; return task.promise;});
    const generating = f.controller.generate(); stop(f.controller); task.resolve(edits); await generating;
    assert.equal(signal.aborted,true); assert.equal(f.writes(),0);
    assert.equal(f.controller.getSnapshot().edits,null);
  }
});

test('invalid, duplicate, out-of-range, multiline entries and provider failures permit retry without writes', async () => {
  for (const bad of [null, {changes:[{index:-1,text:'x'}]}, {changes:[{index:2,text:'两\n行'}]},
    {changes:[{index:1,text:'a'.repeat(81)}]}, {changes:[{index:0,text:'a'},{index:0,text:'b'}]}, new Error('失败')]) {
    let retry = false;
    const f = setup(async () => {if (retry) return edits; if (bad instanceof Error) throw bad; return bad;});
    await f.controller.generate(); assert.ok(f.controller.getSnapshot().error);
    assert.equal(f.controller.getSnapshot().edits,null); assert.equal(f.writes(),0);
    retry = true; await f.controller.generate(); assert.equal(f.controller.getSnapshot().edits.length,3);
  }
});

test('no changes, an empty draft and oversized inputs are distinct from successful adoption', async () => {
  for (const big of [formValues(), formValues({summary:'x'.repeat(4001)}), formValues({skills:Array(101).fill('x')}),
    formValues({projectExperiences:Array(8).fill('t'.repeat(2100))})]) {
    let calls = 0; const f=setup(async () => {calls++; return edits;},big);
    await f.controller.generate(); assert.equal(calls,0); assert.ok(f.controller.getSnapshot().error);
  }
  const f=setup(async () => ({changes:[]})); await f.controller.generate(); f.controller.apply();
  assert.deepEqual(f.controller.getSnapshot().edits,[]); assert.equal(f.writes(),0);
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

