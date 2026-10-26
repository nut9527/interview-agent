import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mysqlImageCandidates, prepareMysqlImage } from './mysql-startup.mjs';
import { createProgressTracker, redactProgress, monitorStage } from './startup-progress.mjs';
import { stopChild } from './dev.mjs';

const primary = 'docker.io/library/mysql:8.4';
const secondary = 'public.ecr.aws/docker/library/mysql:8.4';
const defaults = { env: {}, offline: false, inspect: async () => false, report: () => {} };

test('explicit image or registry is respected and never replaced with public sources', () => {
  assert.deepEqual(mysqlImageCandidates({ MYSQL_IMAGE: 'local/mysql@sha256:abc', DOCKER_REGISTRY: 'registry.local' }), ['local/mysql@sha256:abc']);
  assert.deepEqual(mysqlImageCandidates({ DOCKER_REGISTRY: 'registry.local/team/' }), ['registry.local/team/mysql:8.4']);
});

test('cached secondary image is reused before any network access, including offline', async () => {
  let pulls = 0;
  const image = await prepareMysqlImage({ ...defaults, offline: true,
    inspect: async (candidate) => candidate === secondary, pull: async () => { pulls++; } });
  assert.equal(image, secondary);
  assert.equal(pulls, 0);
});

test('offline missing image fails immediately without a download', async () => {
  let pulls = 0;
  await assert.rejects(prepareMysqlImage({ ...defaults, offline: true, pull: async () => { pulls++; } }), /离线启动缺少 MySQL 镜像/);
  assert.equal(pulls, 0);
});

test('failed default source switches once to the secondary official source', async () => {
  const calls = [];
  const image = await prepareMysqlImage({ ...defaults, pull: async (candidate) => {
    calls.push(candidate);
    if (candidate === primary) throw new Error('download stalled');
  } });
  assert.equal(image, secondary);
  assert.deepEqual(calls, [primary, secondary]);
});

test('two failed sources stop with actionable diagnostics instead of cycling', async () => {
  let pulls = 0;
  await assert.rejects(prepareMysqlImage({ ...defaults, pull: async () => { pulls++; throw new Error('download stalled'); } }), /Docker Desktop.*MYSQL_IMAGE.*--external-db/);
  assert.equal(pulls, 2);
});

test('failed explicit image does not silently download a different image', async () => {
  const calls = [];
  await assert.rejects(prepareMysqlImage({ ...defaults, env: { MYSQL_IMAGE: 'local/mysql:8.4' },
    pull: async (image) => { calls.push(image); throw new Error('unavailable'); } }));
  assert.deepEqual(calls, ['local/mysql:8.4']);
});

test('cancelling a pull prevents fallback or subsequent startup', async () => {
  let cancelled = false;
  let pulls = 0;
  await assert.rejects(prepareMysqlImage({ ...defaults, cancelled: () => cancelled,
    pull: async () => { pulls++; cancelled = true; throw new Error('terminated'); } }), /已取消/);
  assert.equal(pulls, 1);
});

test('repeated Docker layer snapshots do not reset the stall deadline', () => {
  let now = 0;
  const progress = createProgressTracker({ now: () => now });
  const snapshot = '6361e9307160 Downloading 1.049MB/47.94MB\n0b7a0feba039 Downloading 3.146MB/132.4MB\n';
  now = 1;
  progress.write(Buffer.from(snapshot));
  now = 100;
  progress.write(Buffer.from(snapshot));
  assert.equal(progress.changedAt, 1);
  progress.write(Buffer.from('6361e9307160 Downloading 2.049MB/47.94MB\n'));
  assert.equal(progress.changedAt, 100);
});

test('progress parses fragmented UTF-8 and independent stdout/stderr lines', () => {
  const progress = createProgressTracker();
  const message = Buffer.from('下载中\n');
  progress.write(message.subarray(0, 2));
  progress.write(Buffer.from('stderr line\n'), 'stderr');
  progress.write(message.subarray(2));
  assert.equal(progress.latest, '下载中');
  progress.write(Buffer.from('final partial'));
  progress.flush();
  assert.equal(progress.latest, 'final partial');
});

test('console progress removes ANSI codes and hides configured credentials', () => {
  assert.equal(redactProgress('\x1b[32mDownloading 2MB\x1b[0m'), 'Downloading 2MB');
  assert.equal(redactProgress('failed with private-value', { DEEPSEEK_API_KEY: 'private-value' }), 'failed with [已隐藏]');
  assert.doesNotMatch(redactProgress('https://user:pass@registry.local/path'), /user:pass/);
  assert.doesNotMatch(redactProgress('token=unknown-secret'), /unknown-secret/);
});

test('real stalled subprocess is stopped, with latest progress and log location', { timeout: 10000 }, async () => {
  const child = spawn(process.execPath, ['-e', "console.log('Downloading 1MB/100MB'); setInterval(()=>console.log('Downloading 1MB/100MB'),20)"],
    { windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
  child.done = new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  const messages = [];
  try {
    await assert.rejects(monitorStage(child, { label: 'mysql-image', logPath: 'test.log', stop: stopChild,
      report: (message) => messages.push(message), stallTimeout: 500, timeout: 3000, interval: 20, heartbeat: 100 }),
    /没有新进展.*Downloading 1MB\/100MB.*test.log/);
    assert.ok(messages.some((message) => message.includes('[进度]')));
    assert.ok(child.exitCode !== null || child.signalCode !== null);
  } finally { await stopChild(child); }
});
