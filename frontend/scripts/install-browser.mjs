// 下载固定 Playwright 版本对应的 Chromium；缓存和临时文件均留在项目内。
import { mkdir, mkdtemp, realpath, rm } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { configureBrowserEnvironment } from './browser-runtime.mjs';
import { capture } from '../../scripts/dev.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--with-deps')) throw new Error('Usage: install-browser.mjs [--with-deps]');
const parent = join(root, '.runtime');
await mkdir(parent, { recursive: true });
const temporary = await mkdtemp(join(parent, 'browser-install-'));
const controller = new AbortController();
const interrupt = () => controller.abort();
process.once('SIGINT', interrupt);
process.once('SIGTERM', interrupt);
try {
  const env = configureBrowserEnvironment({ ...process.env, TMP: temporary, TEMP: temporary, TMPDIR: temporary,
    PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT: '30000' });
  const cli = fileURLToPath(new URL('../node_modules/playwright/cli.js', import.meta.url));
  const result = await capture(process.execPath, [cli, 'install', 'chromium', '--only-shell', ...args],
    { env, timeout: 300000, signal: controller.signal, onOutput: chunk => process.stdout.write(chunk) });
  process.exitCode = result.code ?? 1;
} finally {
  process.removeListener('SIGINT', interrupt);
  process.removeListener('SIGTERM', interrupt);
  const actual = await realpath(temporary);
  if (!actual.startsWith(await realpath(parent) + sep + 'browser-install-')) throw new Error('Unsafe browser install cleanup');
  await rm(actual, { recursive: true, maxRetries: 5, retryDelay: 200 });
}
