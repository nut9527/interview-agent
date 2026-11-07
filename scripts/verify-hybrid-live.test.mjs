import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer } from 'node:http';
import test from 'node:test';
import { verifyHybridLive } from './verify-hybrid-live.mjs';

async function application(t, { missingSemanticHit = false, failDocumentDelete = false } = {}) {
  const requests = [];
  let password;
  const chunk = { id: 'synthetic:1', heading: '写后读', content: '订单提交后本次请求从主库读取。' };
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    requests.push(request.method + ' ' + url.pathname);
    const reply = (data, status = 200) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ success: status === 200, data }));
    };
    let raw = '';
    for await (const part of request) raw += part;
    const body = raw ? JSON.parse(raw) : undefined;
    if (url.pathname === '/api/auth/register') {
      password = body.password;
      return reply({ token: 'temporary-session' });
    }
    if (request.headers.authorization !== 'Bearer temporary-session') return reply(null, 401);
    if (url.pathname === '/api/knowledge/documents' && request.method === 'POST') {
      return reply({ id: 'synthetic', version: 1, title: body.title, content: body.content, chunks: [chunk] });
    }
    if (url.pathname === '/api/knowledge/documents/synthetic/index') {
      return reply([{ version: 1, profile: 'fixture-model:512', state: 'READY' }]);
    }
    if (url.pathname === '/api/knowledge/search') {
      return reply(url.searchParams.has('documentIds') && !missingSemanticHit
        ? [{ documentId: 'synthetic', version: 1, chunk }] : []);
    }
    if (url.pathname === '/api/knowledge/documents/synthetic' && request.method === 'DELETE') {
      return reply(null, failDocumentDelete ? 500 : 200);
    }
    if (url.pathname === '/api/users/me' && request.method === 'DELETE') {
      return reply(null, body.currentPassword === password ? 200 : 400);
    }
    reply(null, 404);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    const closed = once(server, 'close');
    server.close();
    server.closeAllConnections();
    await closed;
  });
  return { origin: 'http://127.0.0.1:' + server.address().port, requests };
}

test('hybrid live verifier rejects non-loopback origins, paths and credentials before creating resources', async () => {
  for (const origin of ['http://example.com', 'http://127.0.0.1/api', 'http://user:secret@127.0.0.1',
    'http://127.0.0.1?redirect=1', 'http://127.0.0.1#fragment']) {
    await assert.rejects(verifyHybridLive({ origin }), /loopback origin/);
  }
});

test('hybrid live verifier proves semantic-only recall and cleans synthetic resources', async t => {
  const app = await application(t);
  const report = await verifyHybridLive({ origin: app.origin });
  assert.equal(report.lexicalHitCount, 0);
  assert.equal(report.hybridHitCount, 1);
  assert.equal(report.semanticOnlyRecallObserved, true);
  assert.deepEqual(report.cleanup, { documentsDeleted: true, accountDeleted: true, vectorCleanupVerified: false });
  assert.equal(report.scopeIdentity.documentId, 'synthetic');
  assert.deepEqual(app.requests.slice(-2), ['DELETE /api/knowledge/documents/synthetic', 'DELETE /api/users/me']);
  assert.doesNotMatch(JSON.stringify(report), /temporary-session|password|token|hybrid_[0-9a-f]/);
});

test('hybrid live verifier still deletes its document and account when semantic recall fails', async t => {
  const app = await application(t, { missingSemanticHit: true });
  await assert.rejects(verifyHybridLive({ origin: app.origin }), error => {
    assert.match(error.message, /did not retrieve/);
    assert.equal(error.report.cleanup.documentsDeleted, true);
    assert.equal(error.report.cleanup.accountDeleted, true);
    return true;
  });
});

test('hybrid live verifier attempts account cleanup and reports a document cleanup failure', async t => {
  const app = await application(t, { failDocumentDelete: true });
  await assert.rejects(verifyHybridLive({ origin: app.origin }), error => {
    assert.ok(error instanceof AggregateError);
    assert.match(error.message, /Failed to delete this verification document/);
    assert.equal(error.report.cleanup.documentsDeleted, false);
    assert.equal(error.report.cleanup.accountDeleted, true);
    assert.equal(error.report.semanticOnlyRecallObserved, true);
    return true;
  });
});
