import test from 'node:test';
import assert from 'node:assert/strict';
import { assertOwnedContainer, createArguments, provisionSql, POSTGRES } from './rag-postgres.mjs';

const path = 'D:\\Dev\\Project\\2026-se\\.runtime\\rag\\postgres';
const owner = 'test-project';
function owned() {
  return { labels: { 'zhizhida.rag.project': owner }, image: POSTGRES.image,
    mounts: [{ Type: 'bind', Source: path, Destination: '/var/lib/postgresql/data', RW: true }],
    ports: { '5432/tcp': [{ HostIp: '127.0.0.1', HostPort: '15432' }] }, restart: 'unless-stopped', running: true };
}

test('container reuse requires project ownership and the exact loopback-only bind mount', () => {
  assert.doesNotThrow(() => assertOwnedContainer(owned(), path, owner));
  const desktop = owned();
  desktop.mounts[0].Source = '/run/desktop/mnt/host/d/Dev/Project/2026-se/.runtime/rag/postgres';
  assert.doesNotThrow(() => assertOwnedContainer(desktop, path, owner));
  for (const alter of [
    item => { item.labels = {}; },
    item => { item.image = 'postgres:17'; },
    item => { item.mounts[0].Source = 'D:/unrelated/data'; },
    item => { item.mounts[0].Type = 'volume'; },
    item => { item.mounts.push({ Type: 'bind', Source: '/etc', Destination: '/outside', RW: true }); },
    item => { item.ports['5432/tcp'][0].HostIp = '0.0.0.0'; },
    item => { item.ports['5432/tcp'][0].HostPort = '5432'; },
    item => { item.ports['5432/tcp'].push({ HostIp: '::', HostPort: '15432' }); },
    item => { item.restart = 'always'; },
  ]) {
    const info = owned(); alter(info);
    assert.throws(() => assertOwnedContainer(info, path, owner));
  }
});

test('create keeps database data in project and passes password by environment name only', () => {
  const args = createArguments(path, owner);
  assert.equal(args[0], 'create');
  assert.equal(args.at(-1), 'pgvector/pgvector:pg17');
  assert.ok(args.includes('127.0.0.1:15432:5432'));
  assert.ok(args.includes(`type=bind,source=${path},target=/var/lib/postgresql/data`));
  assert.ok(args.includes('POSTGRES_PASSWORD'));
  assert.ok(args.includes('POSTGRES_DB'));
  assert.ok(!args.some(value => value.startsWith('POSTGRES_PASSWORD=')));
  assert.ok(!args.includes('--volume'));
  assert.throws(() => createArguments('D:/project,other/data', owner), /逗号/);
});

test('provisioning preserves existing vectors and validates dimensions before granting DML', () => {
  const sql = provisionSql('a'.repeat(48));
  assert.ok(sql.startsWith('BEGIN;'));
  assert.ok(sql.endsWith('COMMIT;\n'));
  assert.match(sql, /CREATE TABLE IF NOT EXISTS public\.knowledge_vectors/);
  assert.match(sql, /actual IS DISTINCT FROM 'vector\(512\)'/);
  assert.ok(sql.indexOf('RAISE EXCEPTION') < sql.indexOf('ALTER ROLE'));
  assert.doesNotMatch(sql, /DROP TABLE|TRUNCATE|ALTER COLUMN|OWNER TO/);
  assert.match(sql, /NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS/);
  assert.match(sql, /REVOKE CREATE ON SCHEMA public FROM PUBLIC/);
  assert.match(sql, /GRANT USAGE ON SCHEMA public TO agent_vectors/);
  assert.match(sql, /GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public\.knowledge_vectors TO agent_vectors/);
  for (const password of ['', 'short', "'; DROP DATABASE agent_vectors; --", 'G'.repeat(48)])
    assert.throws(() => provisionSql(password), /Invalid application password/);
});
