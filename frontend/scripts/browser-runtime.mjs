import { mkdir, realpath } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
export const browserCachePath = fileURLToPath(new URL('../../.cache/ms-playwright', import.meta.url));
export const browserTemporaryPath = resolve(projectRoot, '.runtime/browser-tmp');

// 下载器与验收脚本共用项目内缓存；在加载 Playwright 前设置，避免使用用户目录。
export function configureBrowserEnvironment(environment = process.env) {
  environment.PLAYWRIGHT_BROWSERS_PATH ||= browserCachePath;
  return environment;
}

function insideProject(path, root = projectRoot) {
  const part = relative(root, path);
  return part !== '..' && !part.startsWith('..' + sep) && !isAbsolute(part);
}

export async function configureBrowserTemporaryDirectory(environment = process.env) {
  const selected = [environment.TMPDIR, environment.TEMP, environment.TMP]
    .filter(value => typeof value === 'string' && value.trim())
    .map(value => resolve(value))
    .find(path => insideProject(path)) || browserTemporaryPath;
  // 先核对最近的已有父目录，避免经符号链接在项目外创建目录。
  const actualRoot = await realpath(projectRoot);
  let ancestor = selected;
  for (;;) {
    try {
      if (!insideProject(await realpath(ancestor), actualRoot)) throw new Error('Browser temporary directory must stay inside the project');
      break;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      ancestor = dirname(ancestor);
    }
  }
  await mkdir(selected, { recursive: true });
  for (const key of ['TMPDIR', 'TEMP', 'TMP']) environment[key] = selected;
  return selected;
}

export function browserLaunchOptions(environment = process.env, legacyChannel) {
  const executablePath = environment.PLAYWRIGHT_EXECUTABLE_PATH?.trim();
  if (executablePath) return { executablePath: resolve(executablePath) };
  const channel = environment.PLAYWRIGHT_BROWSER_CHANNEL?.trim() || legacyChannel?.trim();
  return channel ? { channel } : {};
}

export async function loadChromium() {
  configureBrowserEnvironment();
  await configureBrowserTemporaryDirectory();
  const module = process.env.PLAYWRIGHT_MODULE?.trim() || 'playwright';
  const specifier = isAbsolute(module) || module.startsWith('.') ? pathToFileURL(resolve(module)).href : module;
  // 此文件位于 frontend/scripts，根目录的验收入口也解析 frontend 的锁定依赖。
  const { chromium } = await import(specifier);
  if (typeof chromium?.launchPersistentContext !== 'function') {
    throw new Error('PLAYWRIGHT_MODULE must export a Playwright chromium browser type');
  }
  return chromium;
}
