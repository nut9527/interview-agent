import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, realpath, symlink } from 'node:fs/promises';
import { join, dirname, sep } from 'node:path';
import { createCandidate, verifyCandidate, allowed, safePath, projectRoot, sha256 } from './release-candidate.mjs';
import { checkFixtures } from './release-fixtures.mjs';

async function fixture(t) {
  const parent = join(projectRoot, '.runtime');
  await mkdir(parent, { recursive: true });
  const root = await mkdtemp(join(parent, 'release-test-'));
  t.after(async () => {
    const actual = await realpath(root), base = await realpath(parent);
    assert.ok(actual.startsWith(base + sep + 'release-test-'));
    await rm(actual, { recursive: true });
  });
  const put = async (path, text = 'fixture\r\n') => {
    await mkdir(dirname(join(root, path)), { recursive: true });
    await writeFile(join(root, path), text);
  };
  for (const path of ['README.md', 'backend/pom.xml', 'frontend/package.json', 'frontend/pnpm-lock.yaml',
    'docs/使用指南.md', 'contracts/release-v1.0.json', 'scripts/release-candidate.mjs']) await put(path);
  return { root, put };
}

test('candidate copies only allowed bytes and refuses to overwrite', async t => {
  const { root, put } = await fixture(t);
  for (const path of ['.env', '.runtime/data.db', '1/private.md', 'backend/target/classes/x.class',
    'frontend/node_modules/module.js', 'docs/local.key']) await put(path, 'MUST_NOT_SHIP');
  await put('.env.example', 'DEEPSEEK_API_KEY=\r\n');
  await put('backend/src/main/java/例.java', '中文😀\r\n');
  const result = await createCandidate('s0-test', root);
  const list = JSON.parse(await readFile(join(result.directory, 'FILES.json'), 'utf8'));
  assert.equal(list.length, 9);
  assert.equal(await readFile(join(result.directory, 'payload/backend/src/main/java/例.java'), 'utf8'), '中文😀\r\n');
  assert.equal(result.status, 'INTEGRITY_VERIFIED_NOT_RELEASE_ACCEPTED');
  await assert.rejects(createCandidate('s0-test', root), /EEXIST/);
});

for (const corruption of ['extra', 'missing', 'same-size', 'size', 'manifest', 'inventory']) {
  test(`candidate rejects ${corruption} corruption`, async t => {
    const { root } = await fixture(t);
    const { directory } = await createCandidate('s0-test', root);
    const target = join(directory, 'payload/README.md');
    if (corruption === 'extra') await writeFile(join(directory, 'payload/extra.md'), 'extra');
    if (corruption === 'missing') await rm(target);
    if (corruption === 'same-size') await writeFile(target, 'changed\r\n');
    if (corruption === 'size') await writeFile(target, 'changed');
    if (corruption === 'manifest') await writeFile(join(directory, 'MANIFEST.sha256'), 'invalid\n');
    if (corruption === 'inventory') {
      const path = join(directory, 'FILES.json');
      const rows = JSON.parse(await readFile(path, 'utf8'));
      rows[0].bytes++;
      await writeFile(path, JSON.stringify(rows));
    }
    await assert.rejects(verifyCandidate(directory));
  });
}

test('paths, secrets and output junctions fail closed', async t => {
  for (const path of ['/absolute', '../outside', 'a/../b', 'C:/file', 'a\\b', 'a\tb', 'a\nb', './a', 'a//b'])
    assert.throws(() => safePath(path));
  for (const path of ['.env', '1/file.md', 'docs/private.pem', 'backend/src/application-local.yml', 'docs/.env.example'])
    assert.equal(allowed(path), false);
  const { root, put } = await fixture(t);
  await put('docs/leaked.md', '-----BEGIN PRIVATE KEY-----');
  await assert.rejects(createCandidate('s0-test', root), /Private key/);
  const other = await fixture(t);
  await symlink(root, join(other.root, 'outputs'), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(createCandidate('s0-test', other.root), /Unsafe release directory/);
});

test('S0 registration is deterministic, retains baseline and covers twelve samples in eight families', async () => {
  const result = await checkFixtures();
  assert.equal(result.samples, 12);
  assert.equal(result.families, 8);
  assert.equal(result.paidExecution, 'DISABLED');
  assert.equal(result.heldoutExecuted, false);
  const template = JSON.parse(await readFile(join(projectRoot, 'scripts/fixtures/review-template.json'), 'utf8'));
  assert.equal(template.metadata.configurationSha256, result.configSha256);
  assert.equal(template.rows.length, 13);
  assert.ok(template.rows.every(row => row.conclusion === '未完成' && row.runIdOrReason === '未运行'));
  assert.equal(sha256(await readFile(join(projectRoot, 'contracts/agent-release-criteria.v1.json'))),
    '76bf3f52422cd4ea77bb8e3d6e0678aac1f3144eae1f8d1164cf6bdd3c4368b6');
});
