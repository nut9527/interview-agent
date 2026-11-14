import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const corpusRoot = fileURLToPath(new URL('../backend/src/test/resources/rag-corpus/', import.meta.url));
export const libraryRoot = fileURLToPath(new URL('../backend/src/main/resources/', import.meta.url));
const kinds = new Set(['direct', 'synonym', 'paraphrase', 'multi', 'distractor', 'insufficient']);
const splits = new Set(['development', 'validation']);

export async function loadCorpus(root = corpusRoot) {
  const manifest = JSON.parse(await readFile(resolve(root, 'manifest.json'), 'utf8'));
  assert.equal(manifest.schemaVersion, 1);
  const documents = [], cases = [];
  for (const group of manifest.groups) {
    const path = resolve(root, group);
    const data = JSON.parse(await readFile(path, 'utf8'));
    for (const document of data.documents) {
      const source = resolve(libraryRoot, document.resource.slice(1));
      documents.push({ ...document, content: await readFile(source, 'utf8') });
    }
    cases.push(...data.cases);
  }
  return { manifest, documents, cases };
}

function sections(content) {
  const result = new Map();
  let heading = '';
  for (const line of content.split(/\r?\n/)) {
    const match = /^#{1,6}\s+(.+)$/.exec(line);
    if (match) {
      heading = match[1].trim();
      if (!result.has(heading)) result.set(heading, '');
    } else if (heading) result.set(heading, result.get(heading) + line + '\n');
  }
  return result;
}

export function validateCorpus({ manifest, documents, cases }) {
  const docs = new Map(), families = new Map(), ids = new Set(), questions = new Set();
  for (const doc of documents) {
    assert.match(doc.id, /^[a-z0-9-]+$/);
    assert.ok(!docs.has(doc.id), `Duplicate document: ${doc.id}`);
    assert.ok(splits.has(doc.split), `Invalid split: ${doc.id}`);
    assert.ok(doc.title && doc.family && doc.version && doc.topic, `Missing metadata: ${doc.id}`);
    assert.ok(['source-copy', 'source-excerpt'].includes(doc.kind));
    assert.equal(createHash('sha256').update(doc.content).digest('hex'), doc.contentSha256, `Content changed: ${doc.id}`);
    assert.ok(doc.content.length >= 500, `Document too short: ${doc.id}`);
    assert.ok(doc.sources.length > 0, `Missing sources: ${doc.id}`);
    for (const source of doc.sources) {
      assert.ok(source.title && source.path && source.license, `Missing source: ${doc.id}`);
      assert.match(source.sha256, /^[a-f0-9]{64}$/);
      if (doc.kind === 'source-copy') assert.equal(source.sha256, doc.contentSha256, `Copy differs from source: ${doc.id}`);
      if (doc.kind === 'source-excerpt') assert.ok(source.pages[0] >= 1 && source.pages[1] >= source.pages[0]);
    }
    if (families.has(doc.family)) assert.equal(families.get(doc.family), doc.split, `Family leaks across splits: ${doc.family}`);
    families.set(doc.family, doc.split);
    docs.set(doc.id, { ...doc, sections: sections(doc.content) });
  }
  for (const row of cases) {
    assert.ok(row.id && !ids.has(row.id), `Duplicate case: ${row.id}`);
    ids.add(row.id);
    assert.ok(row.question && !questions.has(row.question), `Duplicate question: ${row.id}`);
    questions.add(row.question);
    assert.ok(kinds.has(row.kind), `Invalid kind: ${row.id}`);
    assert.ok(splits.has(row.split));
    assert.ok(row.answer?.trim(), `Missing answer guidance: ${row.id}`);
    assert.ok(row.scope.length && new Set(row.scope).size === row.scope.length);
    assert.ok(row.scope.length <= 5, `Scope exceeds five documents: ${row.id}`);
    for (const id of row.scope) {
      assert.ok(docs.has(id), `Unknown scope: ${row.id}/${id}`);
      assert.equal(docs.get(id).split, row.split, `Case leaks across splits: ${row.id}/${id}`);
    }
    assert.equal(new Set(row.expected).size, row.expected.length, `Repeated label: ${row.id}`);
    assert.equal(row.shouldAbstain, row.expected.length === 0, `Abstention mismatch: ${row.id}`);
    assert.equal(row.kind === 'insufficient', row.shouldAbstain, `No-answer kind mismatch: ${row.id}`);
    const labels = row.evidence.map(e => `${e.documentId}:${e.heading}`);
    assert.deepEqual([...labels].sort(), [...row.expected].sort(), `Evidence/label mismatch: ${row.id}`);
    for (const evidence of row.evidence) {
      assert.ok(row.scope.includes(evidence.documentId), `Evidence outside scope: ${row.id}`);
      const section = docs.get(evidence.documentId).sections.get(evidence.heading);
      assert.ok(evidence.quote?.trim() && section?.includes(evidence.quote), `Quote not in section: ${row.id}`);
    }
    if (row.kind === 'multi') assert.ok(new Set(row.evidence.map(e => e.documentId)).size >= 2, `Not cross-document: ${row.id}`);
  }
  const countBy = (rows, key) => Object.fromEntries([...new Set(rows.map(r => r[key]))].sort().map(value => [value, rows.filter(r => r[key] === value).length]));
  return {
    id: manifest.id, annotationStatus: manifest.annotationStatus,
    documents: documents.length, characters: documents.reduce((n, d) => n + d.content.length, 0),
    families: families.size, cases: cases.length,
    documentsByTopic: countBy(documents, 'topic'), documentsBySplit: countBy(documents, 'split'),
    casesByKind: countBy(cases, 'kind'), casesBySplit: countBy(cases, 'split'),
  };
}

export async function verifySourceFiles({ documents }) {
  const sources = new Map();
  for (const document of documents) for (const source of document.sources) sources.set(source.path, source);
  for (const source of sources.values()) {
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(source.path)) hash.update(chunk);
    assert.equal(hash.digest('hex'), source.sha256, `Source changed: ${source.path}`);
  }
  return sources.size;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command = process.argv[2] ?? 'check';
  if (!['check', 'sources'].includes(command)) throw new Error('Usage: node scripts/rag-corpus.mjs [check|sources]');
  const corpus = await loadCorpus();
  const summary = validateCorpus(corpus);
  if (command === 'sources') summary.verifiedSourceFiles = await verifySourceFiles(corpus);
  console.log(JSON.stringify(summary, null, 2));
}
