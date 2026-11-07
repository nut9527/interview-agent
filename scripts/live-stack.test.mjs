import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { PROJECT_ROOT } from './dev.mjs';
import { liveStack, readLiveEnvironment } from './live-stack.mjs';

test('live environment allows no dotenv and keeps explicit overrides above file defaults', async () => {
  const parent = join(PROJECT_ROOT, '.runtime');
  await mkdir(parent, { recursive: true });
  const dir = await mkdtemp(join(parent, 'live-config-test-'));
  try {
    const envFile = join(dir, '.env');
    assert.deepEqual(readLiveEnvironment(envFile, { AI_PROVIDER: 'mock' }), { AI_PROVIDER: 'mock' });
    await writeFile(envFile, 'INTERVIEW_RUNS_WORKER_ENABLED=true\nAI_PROVIDER=deepseek\nDEEPSEEK_API_KEY=fixture-only');
    const original = { INTERVIEW_RUNS_WORKER_ENABLED: 'false', DEEPSEEK_API_KEY: '' };
    assert.deepEqual(readLiveEnvironment(envFile, original), {
      INTERVIEW_RUNS_WORKER_ENABLED: 'false', AI_PROVIDER: 'deepseek', DEEPSEEK_API_KEY: '',
    });
    assert.equal(original.INTERVIEW_RUNS_WORKER_ENABLED, 'false');
    await writeFile(envFile, 'INVALID');
    assert.throws(() => readLiveEnvironment(envFile, {}), /KEY=value/);
  } finally {
    const actual = await realpath(dir);
    assert.ok(actual.startsWith(await realpath(parent) + sep + 'live-config-test-'));
    await rm(actual, { recursive: true });
  }
});

test('missing live provider credentials fail before allocating temporary resources', async () => {
  const parent = join(PROJECT_ROOT, '.runtime');
  await mkdir(parent, { recursive: true });
  const before = (await readdir(parent)).filter(name => name.startsWith('live-acceptance-'));
  await assert.rejects(liveStack(true, { environment: {} }), /requires configured API key/);
  assert.deepEqual((await readdir(parent)).filter(name => name.startsWith('live-acceptance-')), before);
});
