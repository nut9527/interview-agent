import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { createRequestClient } from './createRequestClient.ts';
import { resetAccountScope } from './accountScope.ts';
import { ApiError, parseRetryAfter, isRequestCanceled } from './ApiError.ts';

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
