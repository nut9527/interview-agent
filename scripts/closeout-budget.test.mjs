import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile, realpath, rm } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { openBudget, budgetProxy, reservationFor, validatePrice } from './closeout-budget.mjs';
import { evaluationEnv, providerKey } from './evaluate-closeout.mjs';
import { projectRoot } from './release-candidate.mjs';

const price = () => ({ model: 'deepseek-flash', endpoint: 'https://api.deepseek.com/chat/completions', currency: 'CNY',
  contextTokenUpperBound: 1048576, inputMicroCnyPerToken: 2, outputMicroCnyPerToken: 8,
  source: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',
  verifiedAt: new Date(Date.now() - 1000).toISOString(), validUntil: new Date(Date.now() + 3600000).toISOString() });
const body = (overrides = {}) => Buffer.from(JSON.stringify({ model: 'deepseek-flash', stream: false,
  thinking: { type: 'disabled' }, max_tokens: 2000, messages: [{ role: 'user', content: '中文\r\n😀' }], ...overrides }));
const plan = (batchId = 'closeout-1') => ({ batchId, sha256: 'a'.repeat(64), sourceManifestSha256: 'b'.repeat(64), price: price() });
const reply = (usage = { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 }, status = 200) =>
  ({ status, body: Buffer.from(JSON.stringify({ usage, choices: [] })) });
const managed = new Map();
async function managedBudget(path) { const gate = await openBudget(path); managed.set(path, gate); return gate; }

async function fixture(t) {
  const parent = join(projectRoot, '.runtime'); await mkdir(parent, { recursive: true });
  const dir = await mkdtemp(join(parent, 'closeout-budget-test-'));
  t.after(async () => {
    const path = join(dir, 'ledger.jsonl');
    if (managed.has(path)) { await managed.get(path).close(); managed.delete(path); }
    const actual = await realpath(dir);
    assert.ok(actual.startsWith(await realpath(parent) + sep + 'closeout-budget-test-'));
    await rm(actual, { recursive: true });
  });
  return join(dir, 'ledger.jsonl');
}

test('reservation includes model context overhead and rejects unpriced request variants', () => {
  const p = price();
  assert.equal(reservationFor(body(), p).microCny, 2113152);
  assert.equal(reservationFor(body({ max_tokens: 600 }), p).microCny, 2101952);
  for (const change of [{ stream: true }, { model: 'other' }, { thinking: { type: 'enabled' } },
    { max_tokens: 3001 }, { max_tokens: 0 }, { n: 2 }, { messages: [] },
    { messages: [{ content: [{ type: 'image_url' }] }] }]) assert.throws(() => reservationFor(body(change), p));
  assert.throws(() => reservationFor(Buffer.alloc(480001), p));
  assert.throws(() => validatePrice({ ...p, validUntil: new Date(Date.now() - 1).toISOString() }));
  assert.throws(() => validatePrice({ ...p, inputMicroCnyPerToken: 1 }));
});

test('unknown usage retains reservations; insufficient funds never reach sender', async t => {
  const path = await fixture(t), gate = await managedBudget(path);
  await gate.begin(plan()); let sent = 0;
  const send = async () => { sent++; return reply(undefined, 500); };
  await gate.exchange('a1', body(), send); await gate.exchange('a2', body(), send);
  await assert.rejects(gate.exchange('a3', body(), send), /budget/);
  assert.equal(sent, 2);
  assert.deepEqual(gate.snapshot().batches[0].requests.map(r => [r.status, r.retained]),
    [['UNKNOWN', 2113152], ['UNKNOWN', 2113152]]);
});

test('reservation is durable before sending, duplicate IDs neither resend nor deduct', async t => {
  const path = await fixture(t), gate = await managedBudget(path);
  await gate.begin(plan()); let sent = 0;
  const send = async () => {
    sent++;
    const state = JSON.parse((await readFile(path, 'utf8')).trim().split('\n').at(-1)).state;
    assert.equal(state.batches[0].requests[0].retained, 2113152);
    assert.equal(state.batches[0].requests[0].status, 'RESERVED');
    return reply();
  };
  await gate.exchange('same', body(), send);
  await assert.rejects(gate.exchange('same', body({ max_tokens: 600 }), send), /Duplicate/);
  assert.equal(sent, 1);
  assert.equal(gate.snapshot().batches[0].requests[0].retained, 180);
  assert.equal(gate.snapshot().batches[0].requests.length, 1);
});

test('missing, malformed and failed HTTP usage never release money; bound violation stops campaign', async t => {
  for (const [usage, status] of [[null, 200], [{ prompt_tokens: '10', completion_tokens: 20, total_tokens: 30 }, 200],
    [{ prompt_tokens: 10, completion_tokens: 20, total_tokens: 31 }, 200],
    [{ prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 }, 429],
    [{ prompt_tokens: 1048577, completion_tokens: 20, total_tokens: 1048597 }, 200]]) {
    const path = await fixture(t), gate = await openBudget(path);
    try {
      await gate.begin(plan()); await gate.exchange('one', body(), async () => reply(usage, status));
      assert.equal(gate.snapshot().batches[0].requests[0].retained, 2113152);
      if (usage?.prompt_tokens === 1048577) await assert.rejects(gate.exchange('two', body(), async () => reply()), /stopped/);
    } finally { await gate.close(); }
  }
});

test('timeout keeps unknown reservation; locks and interrupted batches fail closed on restart', async t => {
  const path = await fixture(t); let gate = await openBudget(path);
  await assert.rejects(openBudget(path), /EEXIST/);
  await gate.begin(plan());
  await assert.rejects(gate.exchange('lost', body(), async () => { throw new Error('timeout'); }), /timeout/);
  await gate.close(); gate = await openBudget(path);
  try {
    assert.equal(gate.snapshot().batches[0].requests[0].status, 'UNKNOWN');
    await assert.rejects(gate.begin(plan()), /Prior batch/);
    await gate.recover();
    assert.equal(gate.snapshot().batches[0].status, 'FAIL');
    await assert.rejects(gate.begin(plan()), /Unexpected frozen batch/);
    await gate.begin(plan('closeout-2'));
    await assert.rejects(gate.exchange('lost', body(), async () => reply()), /Duplicate/);
  } finally { await gate.close(); }
});

test('two started failures stop campaign across candidate changes; zero-call failures consume no batch', async t => {
  const path = await fixture(t), gate = await managedBudget(path);
  await gate.begin(plan()); await gate.finish('FAIL');
  assert.equal(gate.snapshot().batches[0].status, 'NOT_STARTED');
  await gate.begin(plan()); await gate.exchange('one', body(), async () => reply()); await gate.finish('FAIL');
  await gate.begin({ ...plan('closeout-2'), sourceManifestSha256: 'c'.repeat(64) });
  await gate.exchange('two', body(), async () => reply()); await gate.finish('FAIL');
  await assert.rejects(gate.begin(plan('closeout-3')), /failure limit/);
});

test('attempt cap is independent of money; review pending prevents the next batch', async t => {
  const path = await fixture(t), gate = await managedBudget(path);
  await gate.begin(plan()); let sent = 0;
  const send = async () => { sent++; return reply(); };
  for (let i = 0; i < 8; i++) await gate.exchange(`id-${i}`, body(), send);
  await assert.rejects(gate.exchange('ninth', body(), send), /allowance/);
  assert.equal(sent, 8);
  await gate.finish('REVIEW_PENDING');
  await assert.rejects(gate.begin(plan('closeout-2')), /review/);
});

test('concurrent requests cannot double spend available allowance', async t => {
  const path = await fixture(t), gate = await managedBudget(path);
  await gate.begin(plan());
  let unblock, entered;
  const ready = new Promise(resolve => { entered = resolve; });
  const pending = gate.exchange('first', body(), async () => {
    entered(); await new Promise(resolve => { unblock = resolve; }); return reply();
  });
  await ready;
  await assert.rejects(gate.exchange('second', body(), async () => { throw new Error('must not send'); }), /busy/);
  unblock(); await pending;
  assert.equal(gate.snapshot().batches[0].requests.length, 1);
});

test('partial writes and journal corruption block reopening without deleting evidence', async t => {
  const path = await fixture(t), gate = await openBudget(path);
  await gate.begin(plan()); await gate.close();
  const original = await readFile(path, 'utf8');
  await writeFile(path, original + '{'); await assert.rejects(openBudget(path), /Incomplete/);
  assert.equal(await readFile(path, 'utf8'), original + '{');
  await writeFile(path, original.replace('RUNNING', 'PASS')); await assert.rejects(openBudget(path), /hash mismatch/);
});

test('loopback HTTP gate rejects bad authentication, missing ID and repeat before upstream', async t => {
  const path = await fixture(t), gate = await openBudget(path);
  await gate.begin(plan()); let sent = 0;
  const proxy = await budgetProxy(gate, 'local-token', async () => { sent++; return reply(); });
  try {
    const request = async (token, id) => {
      const headers = { Authorization: `Bearer ${token}` }; if (id) headers['X-Client-Request-Id'] = id;
      const response = await fetch(proxy.url + '/chat/completions', { method: 'POST', headers, body: body() });
      await response.text(); return response.status;
    };
    assert.equal(await request('wrong', 'id1'), 502);
    assert.equal(await request('local-token'), 502);
    assert.equal(await request('local-token', 'id1'), 200);
    assert.equal(await request('local-token', 'id1'), 502);
    assert.equal(sent, 1);
  } finally { await proxy.close(); await gate.close(); }
});

test('evaluation process receives only local credentials and explicit B0 switches', () => {
  const env = evaluationEnv({ PATH: 'tools', DEEPSEEK_API_KEY: 'real-secret', AI_API_KEY: 'other-secret',
    DEEPSEEK_BASE_URL: 'https://other', INTERVIEW_RUNS_LOOP_POLICY: 'S3', JAVA_TOOL_OPTIONS: '-Drisky=true',
    MAVEN_ARGS: '-DskipTests', SPRING_APPLICATION_JSON: '{"ai":{"api-key":"secret"}}' },
  'http://127.0.0.1:1234', 'local-token', 'synthetic.jsonl');
  assert.equal(env.SPRING_PROFILES_ACTIVE, 'agent-evaluation');
  assert.equal(env.INTERVIEW_RUNS_DECISION_PROTOCOL, 'phased-v2');
  assert.equal(env.PATH, 'tools'); assert.equal(env.INTERVIEW_RUNS_LOOP_POLICY, 'B0');
  assert.equal(env.DEEPSEEK_API_KEY, 'local-token'); assert.equal(env.AI_API_KEY, 'local-token');
  assert.equal(env.JAVA_TOOL_OPTIONS, undefined); assert.equal(env.SPRING_APPLICATION_JSON, undefined);
  assert.ok(!JSON.stringify(env).includes('real-secret'));
  assert.equal(providerKey({ DEEPSEEK_API_KEY: 'synthetic', DEEPSEEK_BASE_URL: 'https://api.deepseek.com/' }), 'synthetic');
  assert.throws(() => providerKey({ AI_API_KEY: 'synthetic', DEEPSEEK_BASE_URL: 'https://other.example' }), /differs/);
  assert.throws(() => providerKey({}), /Missing/);
});
