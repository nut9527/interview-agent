import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { loadCorpus, validateCorpus, libraryRoot } from './rag-corpus.mjs';

const corpus = await loadCorpus();
test('application library and retrieval evaluation share the same original documents', async () => {
  const catalog = JSON.parse(await readFile(resolve(libraryRoot, 'reference-library/catalog.json'), 'utf8'));
  assert.deepEqual(catalog.map(item => item.id).sort(), corpus.documents.map(item => item.id).sort());
  for (const item of catalog) {
    const document = corpus.documents.find(doc => doc.id === item.id);
    assert.equal('/reference-library/' + item.resource, document.resource);
    assert.equal(item.title, document.title);
  }
});
test('source corpus preserves original text, grounded labels and disjoint material families', () => {
  const result = validateCorpus(corpus);
  assert.ok(result.documents >= 20);
  assert.ok(result.cases >= 80);
  assert.ok(result.casesByKind.insufficient > 0);
  assert.ok(result.casesBySplit.validation > 0);
});
test('rejects an invented quotation', () => {
  const data = structuredClone(corpus);
  data.cases.find(c => c.evidence.length).evidence[0].quote = '此段不是资料中的真实引文。';
  assert.throws(() => validateCorpus(data), /Quote not in section/);
});
test('detects changes to imported source text', () => {
  const data = structuredClone(corpus);
  data.documents[0].content += '\n替换原文。';
  assert.throws(() => validateCorpus(data), /Content changed/);
});
test('rejects development material in a validation question', () => {
  const data = structuredClone(corpus);
  data.cases.find(c => c.split === 'validation').scope[0] = data.documents.find(d => d.split === 'development').id;
  assert.throws(() => validateCorpus(data), /Case leaks across splits/);
});
test('rejects scopes above the production five-document limit', () => {
  const data = structuredClone(corpus);
  const row = data.cases.find(c => c.scope.length === 5);
  row.scope.push(data.documents.find(d => d.split === row.split && !row.scope.includes(d.id)).id);
  assert.throws(() => validateCorpus(data), /Scope exceeds five documents/);
});
test('does not allow duplicate documents or missing answer guidance', () => {
  const duplicate = structuredClone(corpus);
  duplicate.documents.push(duplicate.documents[0]);
  assert.throws(() => validateCorpus(duplicate), /Duplicate document/);
  const missing = structuredClone(corpus);
  missing.cases[0].answer = '';
  assert.throws(() => validateCorpus(missing), /Missing answer guidance/);
});
