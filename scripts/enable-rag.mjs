import { readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PROJECT_ROOT } from './dev.mjs';
import { ensurePostgres } from './rag-postgres.mjs';
import { ensureEmbedding } from '../services/embedding/launcher.mjs';

// 只改当前项目的显式键，保留用户的模型密钥、注释和其它运行配置。
export function updateEnvironment(source, values) {
  const remaining = new Map(Object.entries(values));
  for (const [key, value] of remaining) {
    if (!/^[A-Z][A-Z0-9_]*$/.test(key) || typeof value !== 'string' || /[\r\n]/.test(value))
      throw new Error('Invalid local RAG configuration');
  }
  const newline = source.includes('\r\n') ? '\r\n' : '\n';
  const lines = source.split(/\r?\n/).map(line => {
    const match = /^(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=/.exec(line.trim());
    if (!match || !Object.hasOwn(values, match[1])) return line;
    remaining.delete(match[1]);
    return `${match[1]}=${values[match[1]]}`;
  });
  if (remaining.size) {
    if (lines.at(-1) !== '') lines.push('');
    lines.push('# Local hybrid RAG; managed by node scripts/enable-rag.mjs');
    for (const [key, value] of remaining) lines.push(`${key}=${value}`);
  }
  return lines.join(newline).replace(/(?:\r?\n)*$/, newline);
}

export async function enableRag({ offline = false } = {}) {
  // 资源就绪后才开启应用开关，失败不写出半份有效配置。
  const postgres = await ensurePostgres();
  const embedding = await ensureEmbedding({ offline });
  if (embedding.dimension !== 512) throw new Error('Local PostgreSQL requires 512-dimensional embeddings');
  const values = {
    ...postgres,
    AGENT_VECTOR_EMBEDDING_URL: embedding.url,
    AGENT_VECTOR_EMBEDDING_MODEL: embedding.model,
    AGENT_VECTOR_DIMENSION: String(embedding.dimension),
    AGENT_VECTOR_EMBEDDING_VERSION: 'bge-zh-v15-q-local-v1',
    AGENT_VECTOR_WORKER_ENABLED: 'true',
    AGENT_VECTOR_ENABLED: 'true',
  };
  const path = join(PROJECT_ROOT, '.env');
  const original = await readFile(path, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return '';
    throw error;
  });
  const next = updateEnvironment(original, values);
  if (next !== original) {
    const pending = join(PROJECT_ROOT, `.env.rag-${process.pid}.tmp`);
    let created = false;
    try {
      await writeFile(pending, next, { flag: 'wx', mode: 0o600 });
      created = true;
      // 不覆盖配置编辑器在服务启动期间做的新修改。
      const current = await readFile(path, 'utf8').catch(error => {
        if (error.code === 'ENOENT') return '';
        throw error;
      });
      if (current !== original) throw new Error('.env changed concurrently; retry enable-rag');
      await rename(pending, path);
    } finally {
      if (created) await unlink(pending).catch(error => { if (error.code !== 'ENOENT') throw error; });
    }
  }
  // 只输出公开连接信息，数据库口令不能进入日志。
  console.log(`[RAG] Ready: ${embedding.model}, ${embedding.dimension} dimensions, ${embedding.url}`);
  console.log('[RAG] Project .env enables hybrid retrieval. Start/restart the backend to load it.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.some(arg => arg !== '--offline')) throw new Error('Usage: node scripts/enable-rag.mjs [--offline]');
  await enableRag({ offline: args.includes('--offline') });
}
