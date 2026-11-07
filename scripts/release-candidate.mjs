// 发布候选只从显式白名单复制；不读取 Git 暂存区，不覆盖已有候选。
import { readFile, writeFile, readdir, lstat, mkdir, realpath } from 'node:fs/promises';
import { resolve, dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

export const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const files = new Set(['README.md', 'LICENSE', '.gitignore', '.gitattributes', '.editorconfig', '.nvmrc', '.env.example', 'start.cmd',
  'docker-compose.yml', 'docker-compose.middleware.yml', 'docker-compose.split.yml',
  'backend/pom.xml', 'backend/Dockerfile', 'backend/.dockerignore',
  ...['.dockerignore', '.eslintrc.json', '.prettierignore', '.prettierrc.json', 'Dockerfile', 'index.html', 'nginx.conf', 'package.json',
    'pnpm-lock.yaml', 'pnpm-workspace.yaml', 'postcss.config.js', 'tailwind.config.js',
    'tsconfig.json', 'tsconfig.node.json', 'vite.config.ts'].map(name => `frontend/${name}`)]);
const trees = ['.github/workflows/', 'backend/.mvn/', 'backend/src/', 'backend/docker/',
  'backend/compatibility/', 'frontend/src/', 'frontend/public/', 'frontend/scripts/', 'services/mcp-reference/', 'contracts/', 'scripts/', 'docs/'];
const forbidden = /^(?:\.git|\.runtime|\.cache|\.m2|\.pnpm-store|node_modules|target|dist|coverage|outputs|tmp|logs|__pycache__)$/;
export const sha256 = value => createHash('sha256').update(value).digest('hex');
export const jsonBytes = value => JSON.stringify(value, null, 2) + '\n';

export function safePath(path) {
  if (typeof path !== 'string' || !path || /[\\\x00-\x1f:]/u.test(path)
      || path.split('/').some(part => !part || part === '.' || part === '..')) throw new Error(`Unsafe relative path: ${path}`);
  return path;
}

export function allowed(path) {
  safePath(path);
  const parts = path.split('/');
  if (parts.some(part => forbidden.test(part)) || /(?:^|\/)\.env(?:\.|$)/.test(path) && path !== '.env.example'
      || /(?:\.pem|\.key|\.p12|\.pfx|\.jks|\.log|\.class|\.tsbuildinfo)$/i.test(path)
      || /(?:^|\/)(?:application-local\.[^/]+|_tmp_[^/]+|.*~)$/.test(path)) return false;
  return files.has(path) || trees.some(prefix => path.startsWith(prefix));
}

async function walk(directory, prefix = '', source = false) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = safePath(prefix + entry.name);
    if (source && forbidden.test(entry.name)) continue;
    if (entry.isSymbolicLink()) throw new Error(`Links are not allowed: ${path}`);
    if (entry.isDirectory()) result.push(...await walk(join(directory, entry.name), path + '/', source));
    else if (entry.isFile()) result.push(path);
    else throw new Error(`Not a regular file: ${path}`);
  }
  return result.sort();
}

async function regularBytes(root, path) {
  safePath(path);
  let current = root;
  for (const part of path.split('/')) {
    current = join(current, part);
    if ((await lstat(current)).isSymbolicLink()) throw new Error(`Links are not allowed: ${path}`);
  }
  if (!(await lstat(current)).isFile()) throw new Error(`Not a regular file: ${path}`);
  return readFile(current);
}

export async function listPayload(root = projectRoot) {
  const paths = [];
  for (const path of files) {
    try { await regularBytes(root, path); paths.push(path); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  for (const prefix of trees) {
    const directory = join(root, prefix);
    try {
      if ((await lstat(directory)).isSymbolicLink()) throw new Error(`Links are not allowed: ${prefix}`);
      paths.push(...(await walk(directory, prefix, true)).filter(allowed));
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return [...new Set(paths)].sort();
}

export async function createCandidate(id, root = projectRoot) {
  if (!/^[a-z0-9][a-z0-9-]{0,79}$/.test(id)) throw new Error('Invalid candidate ID');
  // 每一层先检查，避免经已有目录链接写到项目以外。
  let parent = await realpath(root);
  for (const part of ['outputs', 'release']) {
    parent = join(parent, part);
    await mkdir(parent).catch(error => { if (error.code !== 'EEXIST') throw error; });
    if ((await lstat(parent)).isSymbolicLink() || !(await lstat(parent)).isDirectory()) throw new Error('Unsafe release directory');
  }
  const directory = join(parent, id);
  const paths = await listPayload(root);
  for (const required of ['README.md', 'backend/pom.xml', 'frontend/package.json', 'frontend/pnpm-lock.yaml',
    'docs/使用指南.md', 'contracts/release-v1.0.json', 'scripts/release-candidate.mjs']) {
    if (!paths.includes(required)) throw new Error(`Required release file missing: ${required}`);
  }
  await mkdir(directory); // EEXIST 时直接拒绝，失败候选也不覆盖。
  const entries = [], manifest = [];
  for (const path of paths) {
    const bytes = await regularBytes(root, path);
    if (/^[ \t]*-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[ \t]*\r?$/m.test(bytes.toString('utf8')))
      throw new Error(`Private key marker in release file: ${path}`);
    const destination = join(directory, 'payload', path);
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, bytes, { flag: 'wx' });
    entries.push({ path, bytes: bytes.length });
    manifest.push(`${sha256(bytes)}  payload/${path}\n`);
  }
  const inventory = jsonBytes(entries);
  await writeFile(join(directory, 'FILES.json'), inventory, { flag: 'wx' });
  manifest.push(`${sha256(inventory)}  FILES.json\n`);
  const content = manifest.sort((a, b) => a.slice(66) < b.slice(66) ? -1 : a.slice(66) > b.slice(66) ? 1 : 0).join('');
  await writeFile(join(directory, 'MANIFEST.sha256'), content, { flag: 'wx' });
  await writeFile(join(directory, 'MANIFEST.sha256.digest'), `${sha256(content)}  MANIFEST.sha256\n`, { flag: 'wx' });
  return verifyCandidate(directory);
}

export async function verifyCandidate(directory) {
  if ((await lstat(directory)).isSymbolicLink()) throw new Error('Candidate must not be a link');
  const inventoryBytes = await regularBytes(directory, 'FILES.json');
  const manifest = await regularBytes(directory, 'MANIFEST.sha256');
  const digest = await regularBytes(directory, 'MANIFEST.sha256.digest');
  if (digest.toString('utf8') !== `${sha256(manifest)}  MANIFEST.sha256\n`) throw new Error('Manifest digest mismatch');
  const entries = JSON.parse(inventoryBytes);
  if (!Array.isArray(entries) || entries.some(e => !e || !allowed(e.path) || !Number.isSafeInteger(e.bytes) || e.bytes < 0
      || Object.keys(e).sort().join(',') !== 'bytes,path')) throw new Error('Invalid FILES.json');
  const paths = entries.map(e => e.path);
  if (new Set(paths).size !== paths.length || JSON.stringify(paths) !== JSON.stringify([...paths].sort())) throw new Error('Unsorted or duplicate inventory');
  const actual = await walk(directory);
  const expected = ['FILES.json', 'MANIFEST.sha256', 'MANIFEST.sha256.digest', ...paths.map(p => `payload/${p}`)].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Missing or unlisted candidate file');
  const lines = [`${sha256(inventoryBytes)}  FILES.json\n`];
  for (const entry of entries) {
    const bytes = await regularBytes(directory, `payload/${entry.path}`);
    if (bytes.length !== entry.bytes) throw new Error(`Size mismatch: ${entry.path}`);
    lines.push(`${sha256(bytes)}  payload/${entry.path}\n`);
  }
  const sorted = lines.sort((a, b) => a.slice(66) < b.slice(66) ? -1 : a.slice(66) > b.slice(66) ? 1 : 0).join('');
  if (!manifest.equals(Buffer.from(sorted))) throw new Error('File hash or manifest format mismatch');
  return { directory, files: entries.length, manifestSha256: sha256(manifest), status: 'INTEGRITY_VERIFIED_NOT_RELEASE_ACCEPTED' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, value] = process.argv.slice(2);
  if (command === 'list' && !value) console.log(jsonBytes(await listPayload()));
  else if (command === 'create' && value) console.log(await createCandidate(value));
  else if (command === 'verify' && value) {
    const directory = resolve(projectRoot, value), rel = relative(join(projectRoot, 'outputs/release'), directory);
    if (!rel || rel.startsWith('..') || rel.includes(sep)) throw new Error('Verify a candidate directly inside outputs/release');
    console.log(await verifyCandidate(directory));
  } else throw new Error('Usage: node scripts/release-candidate.mjs list | create <id> | verify outputs/release/<id>');
}
