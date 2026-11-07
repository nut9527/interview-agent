import assert from 'node:assert/strict';
import { access, readFile, readdir } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../', import.meta.url));
const guides = (await readdir(join(root, 'docs'), { recursive: true }))
  .filter(name => name.endsWith('.md')).map(name => join('docs', name));
const contract = JSON.parse(await readFile(join(root, 'contracts/openapi.json'), 'utf8'));

for (const file of ['README.md', 'backend/compatibility/langchain4j/README.md', ...guides]) {
  test(`${file} links to existing files and describes registered API operations`, async () => {
    const path = join(root, file);
    const prose = (await readFile(path, 'utf8')).replace(/^```[^\n]*\n[\s\S]*?^```\s*$/gm, '');
    for (const [, target] of prose.matchAll(/\[[^\]\n]*\]\(([^\s)]+)\)/g)) {
      if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(target)) continue;
      const [filename] = target.split(/[?#]/);
      const destination = resolve(dirname(path), decodeURIComponent(filename));
      const local = relative(root, destination);
      assert.ok(local !== '..' && !local.startsWith('..' + sep) && !isAbsolute(local),
        `Link points outside the project: ${target}`);
      await assert.doesNotReject(access(destination), `Missing link target: ${target}`);
    }
    for (const [, method, route] of prose.matchAll(/`(GET|POST|PUT|PATCH|DELETE) (\/api\/[^\s`?]+)(?:\?[^`]*)?`/g)) {
      assert.ok(contract.paths[route]?.[method.toLowerCase()], `Missing API operation: ${method} ${route}`);
    }
  });
}
