import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const sourceRoot = new URL('../src/', import.meta.url);
export function resolve(specifier, context, next) {
    const local = specifier.startsWith('@/') ? new URL(specifier.slice(2), sourceRoot)
      : specifier.startsWith('.') && context.parentURL?.startsWith(sourceRoot.href)
        ? new URL(specifier, context.parentURL) : null;
    if (local && !/\.(mjs|ts|tsx)$/.test(local.pathname)) {
      for (const suffix of ['.ts', '/index.ts']) {
        const candidate = new URL(`${local.href}${suffix}`);
        if (existsSync(candidate)) return next(candidate.href, context);
      }
    }
    return next(specifier, context);
}

export function load(url, context, next) {
    if (url === new URL('api/request.ts', sourceRoot).href || url === new URL('config/runtime.ts', sourceRoot).href) {
      return { format: 'module-typescript', shortCircuit: true,
        source: readFileSync(fileURLToPath(url), 'utf8').replaceAll('import.meta.env', '({ VITE_USE_MOCK: "false" })') };
    }
    return next(url, context);
}
