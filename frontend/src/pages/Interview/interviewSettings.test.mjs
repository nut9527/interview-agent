import test from 'node:test';
import assert from 'node:assert/strict';
import { buildInterviewRequest } from './interviewSettings.ts';

const input = { resumeId: 7, jobSource: 'custom', job: { id: 'saved', title: '旧岗位', revision: 3 },
  customRole: ' Java 后端 ', customJd: ' 负责接口开发与事务处理。 ', mode: 'GENERAL', difficulty: 'ADVANCED', rounds: 6 };

test('custom JD sends only inline material, selected difficulty and the selected resume', () => {
  assert.deepEqual(buildInterviewRequest(input), { resumeId: 7, targetRole: 'Java 后端',
    jobDescription: '负责接口开发与事务处理。', mode: 'GENERAL', difficulty: 'ADVANCED', totalRounds: 6 });
});

test('switching back to a saved job excludes the retained custom draft', () => {
  assert.deepEqual(buildInterviewRequest({ ...input, jobSource: 'saved' }), { resumeId: 7, targetRole: '旧岗位',
    targetJobId: 'saved', targetJobRevision: 3, mode: 'GENERAL', difficulty: 'ADVANCED', totalRounds: 6 });
});

test('blank and oversized custom material cannot start a session', () => {
  for (const fields of [{ customRole: ' ' }, { customJd: '\n' }, { customRole: 'x'.repeat(161) }, { customJd: 'x'.repeat(20001) }]) {
    assert.throws(() => buildInterviewRequest({ ...input, ...fields }));
  }
  assert.equal(buildInterviewRequest({ ...input, customJd: '原'.repeat(20000) }).jobDescription.length, 20000);
  assert.throws(() => buildInterviewRequest({ ...input, jobSource: 'saved', job: null }));
});
