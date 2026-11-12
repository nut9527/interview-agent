import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PROJECT_ROOT } from './dev.mjs';
import { browserEnvironment, startBrowserStack, wslPathArguments, ownedWslDirectory } from './browser-stack.mjs';

test('WSL path conversion bypasses the shell and preserves spaces and literal shell characters', () => {
  assert.deepEqual(wslPathArguments('Ubuntu', 'D:\\Dev\\Project\\with spaces & $literal\\.runtime\\tmp'),
    ['-d', 'Ubuntu', '--exec', 'wslpath', '-a', 'D:/Dev/Project/with spaces & $literal/.runtime/tmp']);
});

test('WSL path parsing tolerates UTF-16 notices without accepting an unrelated or ambiguous directory', () => {
  const tmp = join(PROJECT_ROOT, '.runtime', 'browser-stack-fixture', 'tmp');
  const expected = '/mnt/d/project/.runtime/browser-stack-fixture/tmp';
  assert.equal(ownedWslDirectory('w\0s\0l\0: proxy notice\r\0\n\0' + expected + '\n', tmp), expected);
  assert.throws(() => ownedWslDirectory('/mnt/d/another/tmp\n', tmp), /Cannot resolve/);
  assert.throws(() => ownedWslDirectory(expected + '\n' + expected + '\n', tmp), /Cannot resolve/);
});

test('browser stack discards developer credentials and configuration while preserving required OS values', () => {
  const dir = join(PROJECT_ROOT, '.runtime', 'browser-stack-fixture');
  const base = {
    PATH: 'os-path', SystemRoot: 'os-root', PLAYWRIGHT_BROWSERS_PATH: 'installed-browser-cache',
    SPRING_APPLICATION_JSON: '{"ai":{"provider":"deepseek"}}', SPRING_CONFIG_LOCATION: 'developer.yml',
    SPRING_PROFILES_ACTIVE: 'production', SPRING_DATASOURCE_URL: 'existing-database',
    DEEPSEEK_API_KEY: 'must-not-propagate', AI_API_KEY: 'must-not-propagate', REDIS_PORT: '6379',
    AGENT_MCP_REFERENCE_URL: 'https://outside.invalid', AGENT_VECTOR_ENABLED: 'true',
    JAVA_TOOL_OPTIONS: '-Dspring.profiles.active=production', NODE_OPTIONS: '--require=external',
    METRICS_TOKEN: 'must-not-propagate', VITE_API_BASE_URL: 'https://outside.invalid',
  };
  const result = browserEnvironment(base, { dir, password: 'owned-database-password' });
  assert.equal(result.PATH, base.PATH);
  assert.equal(result.SystemRoot, base.SystemRoot);
  assert.equal(result.PLAYWRIGHT_BROWSERS_PATH, base.PLAYWRIGHT_BROWSERS_PATH);
  for (const key of ['SPRING_APPLICATION_JSON', 'SPRING_CONFIG_LOCATION',
    'JAVA_TOOL_OPTIONS', 'NODE_OPTIONS', 'METRICS_TOKEN', 'AGENT_MCP_REFERENCE_URL']) assert.equal(result[key], undefined);
  assert.equal(result.SPRING_CONFIG_IMPORT, pathToFileURL(join(dir, 'empty.properties')).href);
  assert.equal(result.AI_PROVIDER, 'mock');
  assert.equal(result.SPRING_PROFILES_ACTIVE, 'test');
  assert.equal(result.INTERVIEW_RUNS_DECISION_PROTOCOL, 'phased-v2');
  assert.equal(result.AI_API_KEY, ''); assert.equal(result.DEEPSEEK_API_KEY, '');
  assert.match(result.SPRING_DATASOURCE_URL, /^jdbc:mysql:\/\/127\.0\.0\.1:13308\/browser_e2e\?/);
  assert.equal(result.SPRING_DATASOURCE_PASSWORD, 'owned-database-password');
  assert.equal(result.REDIS_RATE_PORT, '16379');
  assert.equal(result.AGENT_VECTOR_ENABLED, 'false');
  assert.equal(result.AGENT_MCP_REFERENCE_ENABLED, 'false');
  assert.equal(result.VITE_API_BASE_URL, '');
  assert.ok(result.KNOWLEDGE_IMPORT_SHARED_DIRECTORY.startsWith(dir));
  assert.ok(result.RESUME_PDFBOX_FONT_CACHE_DIR.startsWith(dir));
  assert.notEqual(result.REDIS_NAMESPACE, browserEnvironment(base, { dir, password: 'same' }).REDIS_NAMESPACE);
  assert.equal(base.DEEPSEEK_API_KEY, 'must-not-propagate');
});

test('CI service credentials are fixed and require an explicit CI environment', () => {
  const options = { dir: join(PROJECT_ROOT, '.runtime', 'browser-stack-fixture'), password: 'local-only', ciServices: true };
  assert.throws(() => browserEnvironment({}, options), /requires CI=true/);
  const result = browserEnvironment({ CI: 'true', SPRING_DATASOURCE_PASSWORD: 'developer-password' }, options);
  assert.equal(result.SPRING_DATASOURCE_USERNAME, 'it_user');
  assert.equal(result.SPRING_DATASOURCE_PASSWORD, 'ci_ephemeral_only');
});

test('a cancelled stack fails before inspecting builds or allocating resources', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(startBrowserStack({ signal: controller.signal }), { name: 'AbortError' });
});
