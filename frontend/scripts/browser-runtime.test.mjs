import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, realpath, rm, rmdir, stat } from 'node:fs/promises';
import { join, resolve, sep } from 'node:path';
import { browserCachePath, browserTemporaryPath, browserLaunchOptions, configureBrowserEnvironment,
  configureBrowserTemporaryDirectory, loadChromium } from './browser-runtime.mjs';

const projectRoot = resolve(import.meta.dirname, '../..');
async function temporaryDirectory(t) {
  const parent = join(projectRoot, '.runtime');
  await mkdir(parent, { recursive: true });
  const directory = await mkdtemp(join(parent, 'browser-runtime-test-'));
  t.after(async () => {
    const actual = await realpath(directory);
    assert.ok(actual.startsWith((await realpath(parent)) + sep + 'browser-runtime-test-'));
    await rm(actual, { recursive: true });
  });
  return directory;
}

test('browser install and launch share a project-local cache without overriding explicit configuration', () => {
  const environment = {};
  assert.equal(configureBrowserEnvironment(environment), environment);
  assert.equal(environment.PLAYWRIGHT_BROWSERS_PATH, browserCachePath);
  assert.equal(browserCachePath, resolve(import.meta.dirname, '../../.cache/ms-playwright'));
  const custom = { PLAYWRIGHT_BROWSERS_PATH: resolve('custom-browser-cache') };
  configureBrowserEnvironment(custom);
  assert.equal(custom.PLAYWRIGHT_BROWSERS_PATH, resolve('custom-browser-cache'));
});

test('Chromium is the default; system channels and executable paths require explicit selection', () => {
  assert.deepEqual(browserLaunchOptions({}), {});
  assert.deepEqual(browserLaunchOptions({ PLAYWRIGHT_BROWSER_CHANNEL: ' msedge ' }), { channel: 'msedge' });
  assert.deepEqual(browserLaunchOptions({}, 'msedge'), { channel: 'msedge' });
  assert.deepEqual(browserLaunchOptions({ PLAYWRIGHT_BROWSER_CHANNEL: 'chrome' }, 'msedge'), { channel: 'chrome' });
  assert.deepEqual(browserLaunchOptions({ PLAYWRIGHT_EXECUTABLE_PATH: './browser/chrome', PLAYWRIGHT_BROWSER_CHANNEL: 'msedge' }),
    { executablePath: resolve('./browser/chrome') });
});

test('external system temp defaults are replaced with the project browser temporary directory', async () => {
  const existed = await stat(browserTemporaryPath).then(() => true, error => {
    if (error.code !== 'ENOENT') throw error;
    return false;
  });
  try {
    const outside = resolve(projectRoot, '../external-system-temp');
    const environment = { TMPDIR: outside, TEMP: outside, TMP: outside };
    assert.equal(await configureBrowserTemporaryDirectory(environment), browserTemporaryPath);
    for (const key of ['TMPDIR', 'TEMP', 'TMP']) assert.equal(environment[key], browserTemporaryPath);
  } finally {
    // 此测试只创建默认空目录；如其他验收已开始使用它，保留其内容。
    if (!existed) await rmdir(browserTemporaryPath).catch(error => {
      if (!['ENOTEMPTY', 'EEXIST', 'ENOENT'].includes(error.code)) throw error;
    });
  }
});

test('an explicit project temp path is retained even when the other variables point outside', async t => {
  const directory = await temporaryDirectory(t);
  for (const key of ['TMPDIR', 'TEMP', 'TMP']) {
    const inside = join(directory, key, 'nested');
    const environment = { TMPDIR: resolve(projectRoot, '../external-temp'), TEMP: resolve(projectRoot, '../external-temp') };
    environment[key] = inside;
    assert.equal(await configureBrowserTemporaryDirectory(environment), inside);
    for (const name of ['TMPDIR', 'TEMP', 'TMP']) assert.equal(environment[name], inside);
    assert.ok((await stat(inside)).isDirectory());
  }
});

test('module override loads after cache and parent-process temp configuration', async t => {
  const keys = ['PLAYWRIGHT_MODULE', 'PLAYWRIGHT_BROWSERS_PATH', 'TMPDIR', 'TEMP', 'TMP'];
  const original = Object.fromEntries(keys.map(key => [key, process.env[key]]));
  const temporary = await temporaryDirectory(t);
  try {
    delete process.env.PLAYWRIGHT_BROWSERS_PATH;
    process.env.TMPDIR = temporary;
    process.env.PLAYWRIGHT_MODULE = 'data:text/javascript,' + encodeURIComponent(
      'export const chromium = { launchPersistentContext() {}, cache: process.env.PLAYWRIGHT_BROWSERS_PATH, temporary: process.env.TEMP };');
    const chromium = await loadChromium();
    assert.equal(chromium.cache, browserCachePath);
    assert.equal(chromium.temporary, temporary);
    process.env.PLAYWRIGHT_MODULE = 'data:text/javascript,export const chromium = {}';
    await assert.rejects(loadChromium(), /must export a Playwright chromium/);
  } finally {
    for (const key of keys) {
      if (original[key] === undefined) delete process.env[key];
      else process.env[key] = original[key];
    }
  }
});
