import assert from 'node:assert/strict';
import { once } from 'node:events';
import test from 'node:test';
import { referenceServer } from './server.mjs';

test('publishes a bounded, topic-sensitive read-only tool over MCP HTTP', async t => {
  const server = referenceServer().listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(done => server.close(done)));
  const url = 'http://127.0.0.1:' + server.address().port + '/mcp';
  const rpc = (method, params, headers = {}) => fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  const call = async (query, topK) => (await rpc('tools/call', {
    name: 'reference_search', arguments: { query, topK },
  })).json();
  assert.equal((await (await rpc('initialize', {})).json()).result.protocolVersion, '2025-06-18');
  assert.deepEqual((await (await rpc('tools/list')).json()).result.tools.map(tool => tool.name), ['reference_search']);
  const matches = async (query, topK) => JSON.parse((await call(query, topK)).result.content[0].text);
  assert.equal((await matches('对象作为 HashMap 的键', 1))[0].title, 'Java 21 · Map 的键契约');
  assert.equal((await matches('TTL 过期 索引', 1)).length, 1);
  assert.equal((await matches('RAG 评测 MRR', 1))[0].url, 'https://javaguide.cn/ai/rag/rag-evaluation.html');
  assert.equal((await matches('HTTP 协商缓存 ETag', 1))[0].url, 'https://xiaolincoding.com/network/2_http/http_interview.html');
  assert.deepEqual(await matches('找不到的科幻电影', 3), []);
  assert.equal((await call('索引', 99)).error.code, -32602);
  assert.equal((await rpc('tools/list', {}, { Origin: 'https://untrusted.example' })).status, 403);
});
