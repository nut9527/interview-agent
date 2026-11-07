import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { referenceServer } from '../services/mcp-reference/server.mjs';
import { liveStack } from './live-stack.mjs';

// The production Java MCP client and Node catalog, a Mock model, and a disposable MySQL database.
const server = referenceServer().listen(0, '127.0.0.1');
await once(server, 'listening');
let stack;
try {
  stack = await liveStack(false, { environment: {
    ...process.env, AGENT_MCP_REFERENCE_ENABLED: 'true',
    AGENT_MCP_REFERENCE_URL: 'http://127.0.0.1:' + server.address().port + '/mcp',
    RUN_DISPATCH_MODE: 'database', KNOWLEDGE_DISPATCH_MODE: 'local',
    QUESTION_CACHE_ENABLED: 'false', REDIS_RATE_LIMIT_ENABLED: 'false', AGENT_VECTOR_ENABLED: 'false',
  } });
  const path = stack.origin + '/api/questions/qb-reliability-003';
  const options = () => ({ signal: AbortSignal.timeout(20000), redirect: 'error' });
  assert.equal((await fetch(path + '/references', options())).status, 401);
  const registration = await fetch(stack.origin + '/api/auth/register', {
    ...options(), method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'mcp_' + randomUUID().slice(0, 8), password: randomUUID(), displayName: '资料检索验收' }),
  });
  assert.equal(registration.status, 200);
  const headers = { Authorization: 'Bearer ' + (await registration.json()).data.token };
  const get = url => fetch(url, { ...options(), headers });
  const references = await get(path + '/references');
  assert.equal(references.status, 200);
  const { data } = await references.json();
  assert.equal(data[0].url, 'https://www.rabbitmq.com/docs/confirms');
  assert.ok(data[0].summary.length > 0);
  assert.equal((await get(stack.origin + '/api/questions/unknown/references')).status, 404);

  await new Promise(done => server.close(done));
  assert.equal((await get(path + '/references')).status, 503);
  assert.equal((await get(path)).status, 200);
  console.log('[MCP] Mock 题库经真实 MCP 找到 RabbitMQ 文档；登录、缺失题目及资料服务中断的边界检查通过。');
} finally {
  if (server.listening) await new Promise(done => server.close(done));
  if (stack) await stack.cleanup();
}
