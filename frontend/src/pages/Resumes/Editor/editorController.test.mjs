import test, { beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createEditorController } from './editorController.ts';
import { formValues, differences, mergeChoices, toRequest } from './editorData.ts';
import { readDraft, writeDraft, stageImport, pruneExpiredDrafts, hasResumeDrafts, DRAFT_TTL } from './resumeDraft.ts';
import { resetAccountScope } from '../../../api/accountScope.ts';
import { ApiError } from '../../../api/ApiError.ts';

const profile = (summary = '原文', revision = 1) => ({ id: 5, revision, ...toRequest(formValues({ ownerName: '张三', targetRole: 'Java', summary })) });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
beforeEach(() => { resetAccountScope(); sessionStorage.clear(); });
const setup = (overrides = {}) => {
  const updates = [], creates = [], fills = [], created = [];
  const controller = createEditorController({ userId: 1, key: 5, get: async () => profile(),
    update: async (id, request) => { updates.push(request); return { ...request, id, revision: request.revision + 1 }; },
    create: async request => { creates.push(request); return { ...request, id: 8, revision: 0 }; },
    changed() {}, fill: values => fills.push(values), created: id => created.push(id), ...overrides });
  return { controller, updates, creates, fills, created };
};
const edit = (controller, summary) => controller.edit({ ...controller.getState().values, summary });

test('refresh offers a draft; discarding never writes the server', async () => {
  const first = setup(); await first.controller.load(); edit(first.controller, '未保存\n'); first.controller.dispose();
  const next = setup(); await next.controller.load();
  assert.equal(next.controller.getState().values.summary, '原文');
  assert.equal(next.controller.getState().recovery.values.summary, '未保存\n');
  next.controller.discardRecovery();
  assert.equal(readDraft(1, 5).draft, null); assert.equal(next.updates.length, 0);
});

test('restore retains raw newlines and old revision; stale recovery opens comparison', async () => {
  const first = setup(); await first.controller.load(); edit(first.controller, '未保存\n'); first.controller.dispose();
  const next = setup({ get: async () => profile('其他页面', 2) }); await next.controller.load(); next.controller.recover();
  const state = next.controller.getState();
  assert.equal(state.values.summary, '未保存\n'); assert.equal(state.revision, 1); assert.equal(state.conflict.revision, 2);
  await next.controller.save(); assert.equal(next.updates.length, 0);
});

test('in-flight save preserves later typing and migrates the baseline/revision of its draft', async () => {
  const gate = deferred(); let calls = 0;
  const { controller } = setup({ update: async (id, req) => { calls++; await gate.promise; return { ...req, id, revision: 2 }; } });
  await controller.load(); edit(controller, '提交的内容');
  const saving = controller.save(); const duplicate = controller.save(); edit(controller, '提交后输入');
  gate.resolve(); await saving; await duplicate;
  assert.equal(calls, 1); assert.equal(controller.getState().dirty, true);
  assert.equal(controller.getState().values.summary, '提交后输入');
  const draft = readDraft(1, 5).draft;
  assert.equal(draft.base.summary, '提交的内容'); assert.equal(draft.values.summary, '提交后输入'); assert.equal(draft.revision, 2);
});

test('successful save clears the draft; editing back to baseline also clears it', async () => {
  const { controller } = setup(); await controller.load(); edit(controller, '新内容'); await controller.save();
  assert.equal(controller.getState().dirty, false); assert.equal(readDraft(1, 5).draft, null);
  edit(controller, '改动'); edit(controller, '新内容'); assert.equal(readDraft(1, 5).draft, null);
});

test('new creation migrates only its own draft and keeps post-submit input', async () => {
  const gate = deferred();
  const { controller, created } = setup({ key: 'new', create: async req => { await gate.promise; return { ...req, id: 8, revision: 0 }; } });
  stageImport(1, formValues({ ownerName: '另一份导入' }));
  await controller.load(); edit(controller, '新草稿'); const saving = controller.save(); edit(controller, '新输入'); gate.resolve(); await saving;
  assert.deepEqual(created, [8]); assert.equal(readDraft(1, 'new').draft, null);
  assert.equal(readDraft(1, 8).draft.values.summary, '新输入'); assert.ok(readDraft(1, 'import').draft);
});

test('409 reads latest without losing draft; choices keep independent edits, explicit overlap, then CAS again', async () => {
  let remote = profile(); let attempt = 0;
  const { controller } = setup({ get: async () => remote, update: async (id, req) => {
    attempt++; if (req.revision !== remote.revision) throw new ApiError('冲突', { status: 409 });
    return { ...req, id, revision: req.revision + 1 };
  } });
  await controller.load(); edit(controller, '我的简介');
  remote = { ...profile('他的简介', 2), targetRole: '服务器岗位' };
  await controller.save(); assert.equal(controller.getState().values.summary, '我的简介');
  assert.throws(() => controller.resolve({}), /选择/);
  controller.resolve({ summary: 'local' });
  assert.equal(controller.getState().values.targetRole, '服务器岗位'); assert.equal(controller.getState().revision, 2);
  remote = { ...remote, revision: 3 }; await controller.save();
  assert.equal(controller.getState().conflict.revision, 3); assert.equal(attempt, 2);
});

test('delayed conflict reads are latest-wins and keep new typing', async () => {
  const gates = [deferred(), deferred()]; let reads = 0;
  const { controller } = setup({ get: async () => ++reads === 1 ? profile() : gates[reads - 2].promise });
  await controller.load(); const first = controller.checkConflict(); const second = controller.checkConflict();
  edit(controller, '等待读取时输入'); gates[1].resolve(profile('新版', 3)); await second;
  gates[0].resolve(profile('旧回包', 2)); await first;
  assert.equal(controller.getState().conflict.revision, 3); assert.equal(controller.getState().values.summary, '等待读取时输入');
});

test('load cannot enable saving after a failure; retry works', async () => {
  let fail = true;
  const { controller, updates } = setup({ get: async () => { if (fail) throw new Error('断网'); return profile(); } });
  await controller.load(); await controller.save(); assert.equal(updates.length, 0); assert.equal(controller.getState().ready, false);
  fail = false; await controller.load(); assert.equal(controller.getState().ready, true);
});

test('failed save and failed conflict fetch retain the old revision and draft', async () => {
  let reads = 0;
  const { controller } = setup({ get: async () => { if (++reads > 1) throw new Error('断网'); return profile(); },
    update: async () => { throw new ApiError('冲突', { status: 409 }); } });
  await controller.load(); edit(controller, '不能丢'); await controller.save();
  assert.match(controller.getState().error, /无法读取最新版/); assert.equal(controller.getState().revision, 1);
  assert.equal(readDraft(1, 5).draft.values.summary, '不能丢');
});

test('account reset deletes drafts; late read and write responses cannot repopulate them', async () => {
  const saveGate = deferred(); const readGate = deferred();
  const first = setup({ update: () => saveGate.promise }); await first.controller.load(); edit(first.controller, '账号甲');
  const saving = first.controller.save(); const second = setup({ get: () => readGate.promise }); const loading = second.controller.load();
  resetAccountScope(); saveGate.resolve(profile('甲保存完成', 2)); readGate.resolve(profile('甲')); await saving; await loading;
  assert.equal(sessionStorage.length, 0); assert.equal(first.controller.getState().revision, 1); assert.equal(second.fills.length, 0);
});

test('unmount aborts requests and late create cannot navigate or migrate draft', async () => {
  const gate = deferred(); let signal;
  const { controller, created } = setup({ key: 'new', create: (_req, abort) => { signal = abort; return gate.promise; } });
  await controller.load(); edit(controller, '新建'); const pending = controller.save(); controller.dispose();
  assert.equal(signal.aborted, true); gate.resolve(profile()); await pending;
  assert.equal(created.length, 0); assert.ok(readDraft(1, 'new').draft);
});

test('drafts isolate users and resume IDs, reject corruption, expire after 24h', async () => {
  const { controller } = setup(); await controller.load(); edit(controller, '私有内容');
  assert.equal(readDraft(2, 5).draft, null); assert.equal(readDraft(1, 6).draft, null);
  const draft = readDraft(1, 5).draft;
  assert.equal(readDraft(1, 5, draft.updatedAt + DRAFT_TTL + 1).draft, null);
  writeDraft({ ...draft, values: { ...draft.values, details: { projects: 'invalid' } } });
  assert.equal(readDraft(1, 5).draft, null);
});

test('storage denied is a visible warning and does not discard in-memory input or prevent server save', async () => {
  const { controller } = setup(); await controller.load();
  const storage = globalThis.sessionStorage;
  globalThis.sessionStorage = { setItem() { throw new Error('quota'); }, removeItem() { throw new Error('denied'); } };
  try {
    edit(controller, '内存内容'); assert.match(controller.getState().storageError, /未能保存/);
    await controller.save(); assert.equal(controller.getState().dirty, false); assert.equal(controller.getState().values.summary, '内存内容');
  } finally { globalThis.sessionStorage = storage; }
});

test('parse staging has no writes; correction is submitted only on confirmation', async () => {
  stageImport(1, formValues({ ownerName: '解析姓名', targetRole: '待核对' }));
  const { controller, creates } = setup({ key: 'import', freshImport: true }); await controller.load();
  assert.equal(creates.length, 0); assert.equal(controller.getState().imported, true);
  controller.edit({ ...controller.getState().values, ownerName: '纠正姓名' }); await controller.save();
  assert.equal(creates[0].ownerName, '纠正姓名'); assert.equal(readDraft(1, 'import').draft, null);
});

test('equal server and recovered content clears leftover draft after a lost response', async () => {
  const first = setup(); await first.controller.load(); edit(first.controller, '已提交'); first.controller.dispose();
  const second = setup({ get: async () => profile('已提交', 2) }); await second.controller.load();
  assert.equal(second.controller.getState().recovery, null); assert.equal(readDraft(1, 5).draft, null);
});

test('contact conflicts are independent; education/project lists are selected as units', () => {
  const base = formValues(); const local = structuredClone(base); const server = structuredClone(base);
  local.details.contact.phone = '123456'; server.details.contact.email = 'test@example.com';
  local.details.projects = [{ name: '甲', contributions: [] }]; server.details.projects = [{ name: '乙', contributions: [] }];
  assert.equal(differences(base, local, server).length, 3);
  const merged = mergeChoices(base, local, server, { 'details.projects': 'server' });
  assert.equal(merged.details.contact.phone, '123456'); assert.equal(merged.details.contact.email, 'test@example.com');
  assert.equal(merged.details.projects[0].name, '乙');
});

test('expiration sweeps only resume drafts and logout detection is account scoped', async () => {
  const { controller } = setup(); await controller.load(); edit(controller, '草稿');
  const draft = readDraft(1, 5).draft;
  sessionStorage.setItem('unrelated', 'preserve');
  assert.equal(hasResumeDrafts(1), true); assert.equal(hasResumeDrafts(2), false);
  pruneExpiredDrafts(draft.updatedAt + DRAFT_TTL + 1);
  assert.equal(hasResumeDrafts(1), false); assert.equal(sessionStorage.getItem('unrelated'), 'preserve');
});

test('corrupt JSON is removed and the editor reports why recovery was skipped', async () => {
  sessionStorage.setItem('zhizhida:resume-draft:v1:1:5', '{broken');
  const { controller } = setup(); await controller.load();
  assert.match(controller.getState().storageError, /损坏/); assert.equal(sessionStorage.length, 0);
  assert.equal(controller.getState().values.summary, '原文');
});
