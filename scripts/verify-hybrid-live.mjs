import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const fixturePath = new URL('../backend/src/test/resources/knowledge-retrieval.json', import.meta.url);
const reportPath = new URL('../docs/evidence/hybrid-live.json', import.meta.url);
const sleep = ms => new Promise(done => setTimeout(done, ms));

function localOrigin(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Hybrid verification requires an HTTP loopback origin without credentials or a path');
  }
  return url.origin;
}

/** Runs only synthetic searches against the local application; never calls the answer model. */
export async function verifyHybridLive({ origin = 'http://127.0.0.1:8080' } = {}) {
  origin = localOrigin(origin); // Validate before reading fixtures or creating any account.
  const fixtureText = await readFile(fixturePath, 'utf8');
  const fixture = JSON.parse(fixtureText);
  const source = fixture.documents.find(document => document.id === 'consistency');
  const question = fixture.comparisonCases.find(item => item.id === 'P01');
  assert.ok(source?.content && question?.question, 'The fixed semantic-only fixture is missing');
  const username = 'hybrid_' + randomBytes(6).toString('hex');
  const password = 'Aa1!' + randomBytes(18).toString('base64url');
  let token;
  let document;
  let failure;
  const cleanupFailures = [];
  const report = {
    schemaVersion: 1,
    recordedAt: new Date().toISOString(),
    origin,
    scope: 'Running loopback backend, synthetic account and document, actual configured vector index; no answer-model calls',
    fixtureSha256: createHash('sha256').update(fixtureText).digest('hex'),
    caseId: question.id,
    paidCalls: 0,
    documentIds: [],
    cleanup: { documentsDeleted: false, accountDeleted: false, vectorCleanupVerified: false },
  };

  const api = async (path, { method = 'GET', body, timeout = 10000 } = {}) => {
    let response;
    try {
      response = await fetch(origin + '/api' + path, {
        method,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: 'error',
        signal: AbortSignal.timeout(timeout),
      });
    } catch {
      throw new Error(`${method} ${path.split('?')[0]} did not complete`);
    }
    if (response.status !== 200) throw new Error(`${method} ${path.split('?')[0]} returned HTTP ${response.status}`);
    let result;
    try { result = await response.json(); }
    catch { throw new Error(`${method} ${path.split('?')[0]} returned invalid JSON`); }
    if (result.success !== true) throw new Error(`${method} ${path.split('?')[0]} was not successful`);
    return result.data;
  };

  try {
    const auth = await api('/auth/register', {
      method: 'POST', body: { username, password, displayName: '混合检索临时验收' },
    });
    token = auth?.token;
    assert.ok(typeof token === 'string' && token.length > 0, 'Registration did not return a session token');
    document = await api('/knowledge/documents', {
      method: 'POST', body: { title: source.title, content: source.content },
    });
    assert.ok(document?.id && document.version === 1 && Array.isArray(document.chunks), 'Invalid synthetic document response');
    report.documentIds.push(document.id);
    report.scopeIdentity = {
      documentId: document.id,
      version: document.version,
      contentSha256: createHash('sha256').update(source.content).digest('hex'),
    };

    const deadline = Date.now() + 180000;
    let ready;
    let lastState = 'MISSING';
    while (Date.now() < deadline) {
      const rows = await api('/knowledge/documents/' + encodeURIComponent(document.id) + '/index', {
        timeout: Math.max(1, Math.min(10000, deadline - Date.now())),
      });
      assert.ok(Array.isArray(rows), 'Index status must be a list');
      const current = rows.filter(row => Number(row.version) === document.version);
      assert.ok(new Set(current.map(row => row.profile)).size <= 1,
        'A new synthetic document has conflicting index profiles; check running workers');
      const latest = current.at(-1);
      lastState = latest?.state || 'MISSING';
      if (latest?.state === 'READY') { ready = latest; break; }
      assert.notEqual(lastState, 'DELETED', 'Synthetic document index was deleted during verification');
      const remaining = deadline - Date.now();
      if (remaining > 0) await sleep(Math.min(1000, remaining));
    }
    assert.ok(ready, `Vector index did not become READY within 180 seconds; last state=${lastState}`);
    assert.ok(typeof ready.profile === 'string' && ready.profile.length > 0, 'READY index has no profile');
    report.indexProfile = ready.profile;
    report.indexState = ready.state;

    const query = encodeURIComponent(question.question);
    // An unscoped search deliberately uses the lexical path. This account owns only our synthetic document.
    const lexical = await api('/knowledge/search?query=' + query);
    assert.ok(Array.isArray(lexical), 'Lexical search must return a list');
    report.lexicalHitCount = lexical.length;
    assert.equal(lexical.length, 0, 'The semantic-only fixture now has lexical hits; it cannot prove vector recall');

    const hybrid = await api('/knowledge/search?query=' + query + '&documentIds=' + encodeURIComponent(document.id));
    assert.ok(Array.isArray(hybrid), 'Hybrid search must return a list');
    const originals = new Map(document.chunks.map(chunk => [chunk.id, chunk]));
    for (const hit of hybrid) {
      assert.equal(hit.documentId, document.id, 'Hybrid search returned an unauthorized document');
      assert.equal(hit.version, document.version, 'Hybrid search returned the wrong version');
      const original = originals.get(hit.chunk?.id);
      assert.ok(original, 'Hybrid search returned an unknown chunk');
      assert.equal(hit.chunk.content, original.content, 'Hybrid search changed archived evidence');
      assert.ok(original.content.split(/\r?\n/).some(line => line.trim() && !/^#{1,6}\s+/.test(line.trim())),
        'A heading-only chunk was returned as evidence');
    }
    assert.ok(hybrid.some(hit => hit.chunk.heading === '写后读'), 'Hybrid search did not retrieve the expected read-after-write evidence');
    report.hybridHitCount = hybrid.length;
    report.retrievedHeadings = [...new Set(hybrid.map(hit => hit.chunk.heading))];
    report.semanticOnlyRecallObserved = true;
  } catch (error) {
    failure = error;
  } finally {
    // Only identifiers and credentials created by this invocation are used for deletion.
    if (token && document?.id) {
      try {
        await api('/knowledge/documents/' + encodeURIComponent(document.id), { method: 'DELETE' });
        report.cleanup.documentsDeleted = true;
      } catch { cleanupFailures.push(new Error('Failed to delete this verification document')); }
    }
    if (token) {
      try {
        await api('/users/me', { method: 'DELETE', body: { currentPassword: password } });
        report.cleanup.accountDeleted = true;
      } catch { cleanupFailures.push(new Error('Failed to delete this verification account')); }
    }
  }
  // The caller checks asynchronous derived-vector deletion through PostgreSQL using scopeIdentity/indexProfile.
  if (failure || cleanupFailures.length) {
    const error = new AggregateError([...(failure ? [failure] : []), ...cleanupFailures],
      [failure?.message, ...cleanupFailures.map(error => error.message)].filter(Boolean).join('; '));
    error.report = report; // No session token, password, or account name is included.
    throw error;
  }
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length > 1) throw new Error('Usage: node scripts/verify-hybrid-live.mjs [http://127.0.0.1:8080]');
  try {
    const report = await verifyHybridLive(args[0] ? { origin: args[0] } : {});
    await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
    console.log(`[hybrid] lexical=${report.lexicalHitCount}, hybrid=${report.hybridHitCount}, semantic-only recall verified; synthetic account removed. Derived-vector cleanup requires the PostgreSQL check.`);
  } catch (error) {
    console.error('[hybrid] ' + error.message);
    process.exitCode = 1;
  }
}
