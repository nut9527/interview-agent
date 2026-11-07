import assert from 'node:assert/strict';
import test from 'node:test';
import { updateEnvironment } from './enable-rag.mjs';

test('local RAG settings preserve unrelated secrets and comments and are idempotent', () => {
  const original = '# user config\r\nAI_API_KEY="a=b#c"\r\nAGENT_VECTOR_ENABLED=false\r\nDEV_FRONTEND_PORT=5174\r\n';
  const values = { AGENT_VECTOR_ENABLED: 'true', AGENT_VECTOR_DIMENSION: '512' };
  const result = updateEnvironment(original, values);
  assert.ok(result.includes('AI_API_KEY="a=b#c"\r\n'));
  assert.ok(result.includes('DEV_FRONTEND_PORT=5174\r\n'));
  assert.ok(result.includes('AGENT_VECTOR_ENABLED=true\r\n'));
  assert.ok(result.includes('AGENT_VECTOR_DIMENSION=512\r\n'));
  assert.equal(updateEnvironment(result, values), result);
});

test('duplicate relevant keys agree and injected environment lines are rejected', () => {
  assert.equal(updateEnvironment('export AGENT_VECTOR_ENABLED=false\nAGENT_VECTOR_ENABLED=false\n',
    { AGENT_VECTOR_ENABLED: 'true' }), 'AGENT_VECTOR_ENABLED=true\nAGENT_VECTOR_ENABLED=true\n');
  assert.throws(() => updateEnvironment('', { AGENT_VECTOR_ENABLED: 'true\nOTHER=bad' }));
});
