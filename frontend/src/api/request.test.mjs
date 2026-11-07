import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { createRequestClient } from './createRequestClient.ts';
import { resetAccountScope } from './accountScope.ts';
import { ApiError, parseRetryAfter, isRequestCanceled } from './ApiError.ts';
import { validateKnowledgeUpload } from './modules/knowledgeUpload.ts';
import { knowledgeService } from './modules/knowledge.ts';
import { resumeService } from './modules/resume.ts';
import request from './request.ts';

afterEach(() => resetAccountScope());
const response = (config, status, data, headers = {}) => ({
  config, status, data, headers: new AxiosHeaders(headers), statusText: '',
});
const failure = (config, status, data, headers) => new AxiosError('request failed', 'ERR_BAD_RESPONSE',
  config, undefined, response(config, status, data, headers));
const makeClient = (adapter, extra = {}) => createRequestClient({
  baseURL: '', getToken: () => 'token-a', onUnauthorized() {}, adapter, ...extra,
});

test('HTTP errors retain status, business code, request id, Retry-After and original cause', async () => {
  let original;
  const client = makeClient(async (config) => {
    original = failure(config, 429, { success: false, message: 'quota', code: 'QUOTA_EXCEEDED' },
      { 'X-Request-ID': 'trace-123', 'Retry-After': '12' });
    throw original;
  });
  await assert.rejects(client.get('/api/test'), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 429);
    assert.equal(error.code, 'QUOTA_EXCEEDED');
    assert.equal(error.requestId, 'trace-123');
    assert.equal(error.retryAfter, '12');
    assert.equal(error.retryAfterMs, 12000);
    assert.equal(error.cause, original);
    assert.equal(error.transient, true);
    return true;
  });
});

test('business rejection with HTTP 200 stays structured; absent backend codes stay undefined', async () => {
  const client = makeClient(async (config) => ({
    ...response(config, 200, { success: false, message: 'rejected' }),
    headers: { 'X-Request-ID': 'trace-business' },
  }));
  await assert.rejects(client.get('/api/test'), (error) => {
    assert.equal(error.status, 200);
    assert.equal(error.code, undefined);
    assert.equal(error.requestId, 'trace-business');
    return true;
  });
});

test('Retry-After supports seconds and HTTP dates, rejects malformed numbers', () => {
  const now = Date.parse('2026-10-01T00:00:00Z');
  assert.equal(parseRetryAfter('Thu, 01 Oct 2026 00:00:20 GMT', now), 20000);
  assert.equal(parseRetryAfter('Thu, 01 Oct 2026 00:00:00 GMT', now + 1), 0);
  for (const value of [undefined, '', 'bad', '-1', '1.5', 'Infinity']) assert.equal(parseRetryAfter(value, now), undefined);
});

test('non-JSON failures and timeouts keep HTTP/transport identity', async () => {
  const client = makeClient(async (config) => { throw failure(config, 503, '<html>Unavailable</html>'); });
  await assert.rejects(client.get('/api/test'), (error) => error.status === 503 && error.transient);
  const timeout = makeClient(async (config) => { throw new AxiosError('timeout', 'ECONNABORTED', config); });
  await assert.rejects(timeout.get('/api/test'), (error) => error.transportCode === 'ECONNABORTED' && error.transient);
});

test('network failures have readable messages while retaining their transport identity', async () => {
  const original = new AxiosError('Network Error', 'ERR_NETWORK');
  const client = makeClient(async () => { throw original; });
  await assert.rejects(client.get('/api/knowledge/imports/jobs'), (error) => {
    assert.match(error.message, /网络连接/);
    assert.equal(error.transportCode, 'ERR_NETWORK');
    assert.equal(error.cause, original);
    assert.equal(error.transient, true);
    return true;
  });
});

test('knowledge uploads accept the Redis PDF size and enforce each format boundary', () => {
  assert.doesNotThrow(() => validateKnowledgeUpload({ name: '图解Redis.pdf', size: 84493057 }));
  for (const [name, size, limit] of [['book.PDF', 100 * 1024 * 1024, '100 MB'],
    ['notes.docx', 30 * 1024 * 1024, '30 MB'], ['notes.txt', 400000, '400 KB'],
    ['notes.md', 400000, '400 KB'], ['notes.markdown', 400000, '400 KB']]) {
    assert.doesNotThrow(() => validateKnowledgeUpload({ name, size }));
    assert.throws(() => validateKnowledgeUpload({ name, size: size + 1 }),
      error => error.code === 'FILE_TOO_LARGE' && error.message.includes(limit));
  }
  assert.throws(() => validateKnowledgeUpload({ name: 'empty.pdf', size: 0 }), /为空/);
  assert.throws(() => validateKnowledgeUpload({ name: 'notes.exe', size: 10 }), /请上传/);
});

test('oversized knowledge files are rejected before any request; valid files retain multipart data', async () => {
  const original = request.defaults.adapter;
  const sent = [];
  request.defaults.adapter = async config => {
    sent.push(config);
    return response(config, 200, { success: true, data: { id: 'upload-job' } });
  };
  try {
    await assert.rejects(knowledgeService.startImport({ name: 'large.pdf', size: 100 * 1024 * 1024 + 1 }), /100 MB/);
    assert.equal(sent.length, 0);
    const file = new File(['%PDF-test'], '资料.pdf', { type: 'application/pdf' });
    assert.equal((await knowledgeService.startImport(file)).id, 'upload-job');
    assert.equal(sent.length, 1);
    assert.equal(sent[0].url, '/api/knowledge/imports/jobs');
    assert.equal(sent[0].data.get('file').name, file.name);
    assert.equal(await sent[0].data.get('file').text(), await file.text());
  } finally { request.defaults.adapter = original; }
});

test('page cancellation retains cancellation semantics and releases listeners', async () => {
  const local = new AbortController();
  let reject;
  let config;
  const client = makeClient((value) => { config = value; return new Promise((_, fail) => { reject = fail; }); });
  const pending = client.get('/api/test', { signal: local.signal });
  local.abort();
  assert.equal(config.signal.aborted, true);
  reject(new CanceledError('canceled', config));
  await assert.rejects(pending, isRequestCanceled);
});

test('AI resume import sends the selected file once and surfaces recognition errors before saving', async () => {
  const original = request.defaults.adapter;
  const calls = [];
  request.defaults.adapter = async config => {
    calls.push(config);
    throw failure(config, 502, { success: false, message: '模型返回的字段无效，请重试' });
  };
  try {
    const file = new File(['synthetic resume'], 'sample.docx');
    await assert.rejects(resumeService.parseDocument(file), error => error.status === 502 && error.message.includes('字段无效'));
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/resumes/import/parse');
    assert.equal(calls[0].data.get('file').name, file.name);
    assert.ok(calls[0].timeout > request.defaults.timeout);
  } finally { request.defaults.adapter = original; }
});

test('account change aborts old requests and a late 401 cannot clear the new identity', async () => {
  let reject;
  let config;
  let token = 'old-token';
  let unauthorized = 0;
  const client = makeClient((value) => { config = value; return new Promise((_, fail) => { reject = fail; }); }, {
    getToken: () => token, onUnauthorized: () => unauthorized++,
  });
  const pending = client.get('/api/private');
  assert.equal(config.headers.Authorization, 'Bearer old-token');
  token = 'new-token';
  resetAccountScope();
  assert.equal(config.signal.aborted, true);
  reject(failure(config, 401, { success: false, message: 'expired' }));
  await assert.rejects(pending, isRequestCanceled);
  assert.equal(unauthorized, 0);
});

test('late success from an adapter that ignores abort is discarded', async () => {
  let resolve;
  let config;
  const client = makeClient((value) => { config = value; return new Promise((done) => { resolve = done; }); });
  const pending = client.get('/api/private');
  resetAccountScope();
  resolve(response(config, 200, { success: true, data: 'private-a' }));
  await assert.rejects(pending, isRequestCanceled);
});

test('only a current authenticated 401 expires the session', async () => {
  let expired = 0;
  const adapter = async (config) => { throw failure(config, 401, { message: 'unauthorized' }); };
  const client = makeClient(adapter, { onUnauthorized: () => expired++ });
  await assert.rejects(client.get('/api/private'), (error) => error.status === 401);
  assert.equal(expired, 1);
  const anonymous = makeClient(adapter, { getToken: () => null, onUnauthorized: () => expired++ });
  await assert.rejects(anonymous.post('/api/auth/login'), (error) => error.status === 401);
  assert.equal(expired, 1);
});
