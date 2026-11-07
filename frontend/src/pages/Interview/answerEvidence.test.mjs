import test from 'node:test';
import assert from 'node:assert/strict';
import { answerEvidence, referenceEvidence } from './answerEvidence.ts';

const evidence = { sourceId: 's:answer:1', sourceType: 'ANSWER_SELF_REPORT', validity: 'VALID',
  normalizationVersion: 'RAW_UTF16', offsetStart: 1, offsetEnd: 3, quote: '😀' };
test('UTF-16 highlighting preserves Chinese, CRLF, emoji and whitespace', () => {
  assert.deepEqual(answerEvidence(evidence, 's:answer:1', '甲😀\r\n乙 '), { before: '甲', quote: '😀', after: '\r\n乙 ' });
});
test('archived question and knowledge excerpts resolve only against their frozen version and identity', () => {
  const base = { validity: 'VALID', normalizationVersion: 'RAW_UTF16', offsetStart: 0, offsetEnd: 3, quote: '唯一键' };
  const training = { question: { id: 'q', version: 2, referenceAnswer: '唯一键约束重复请求' },
    knowledge: [{ id: 'd', version: 3, sha256: 'doc-hash', content: '唯一键也要核对返回结果', excerpts: [{ chunkId: 'c', content: '唯一键也要核对返回结果' }] }] };
  const q = { ...base, sourceType: 'QUESTION_REFERENCE', sourceId: 'q', sourceVersion: '2' };
  const k = { ...base, sourceType: 'KNOWLEDGE_EXCERPT', sourceId: 'knowledge/d/c', sourceVersion: '3@doc-hash' };
  assert.equal(referenceEvidence(q, training).quote, '唯一键');
  assert.equal(referenceEvidence(k, training).quote, '唯一键');
  for (const invalid of [{ ...q, sourceVersion: '3' }, { ...q, sourceId: 'other' }, { ...k, sourceVersion: '3@changed' },
    { ...k, sourceId: 'knowledge/d/other' }, { ...k, validity: 'REVOKED' }, { ...k, normalizationVersion: 'LF_V1' }, { ...k, sourceType: 'RESUME' }])
    assert.equal(referenceEvidence(invalid, training), null);
  assert.equal(referenceEvidence(q), null);
  training.knowledge[0].content = 'different original';
  assert.equal(referenceEvidence(k, training), null);
});
test('cannot search another occurrence, another session, revoked or missing answer', () => {
  for (const changed of [{ offsetStart: 0, offsetEnd: 2 }, { validity: 'REVOKED' }, { sourceId: 'other:answer:1' },
    { sourceType: 'KNOWLEDGE' }, { normalizationVersion: 'unknown' }, { offsetStart: -1 }, { offsetEnd: 10 }])
    assert.equal(answerEvidence({ ...evidence, ...changed }, 's:answer:1', '甲😀'), null);
  assert.equal(answerEvidence(evidence, 's:answer:1', undefined), null);
  assert.equal(answerEvidence({ ...evidence, offsetEnd: 2, quote: '\uD83D' }, 's:answer:1', '甲😀'), null);
});
test('LF contract is explicit and preserves offset semantics', () => {
  assert.deepEqual(answerEvidence({ ...evidence, normalizationVersion: 'LF_V1', offsetStart: 2, offsetEnd: 3, quote: '乙' },
    's:answer:1', '甲\r\n乙'), { before: '甲\n', quote: '乙', after: '' });
});
