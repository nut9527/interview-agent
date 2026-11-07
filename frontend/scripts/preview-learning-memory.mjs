// 本地浏览器夹具使用生产构建和隔离 MySQL 的测试导出。
// 服务不连接业务数据库或外部模型。
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, join, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertPortFree } from '../../scripts/dev.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = join(root, 'frontend/dist');
const initial = JSON.parse(await readFile(join(root, 'backend/target/learning-memory-fixture.json'), 'utf8'));
let fixture = structuredClone(initial);
await assertPortFree(15174);
const server = createServer(async (req, res) => {
  const ok = data => { res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify({ success: true, data, message: '' })); };
  const reject = status => res.writeHead(status).end();
  try {
    const url = new URL(req.url, 'http://127.0.0.1:15174');
    if (url.pathname.startsWith('/api/')) {
      if (url.pathname === '/api/learning/observations') return ok(fixture);
      if (url.pathname === '/api/learning/observations/export') return ok({ formatVersion: 'learning-memory-v1', exportedAt: new Date().toISOString(), preferences: fixture.preferences, observations: fixture.observations, hasMore: false, nextCursor: null });
      if (req.method === 'POST' || req.method === 'PUT') {
        let raw = ''; for await (const chunk of req) { raw += chunk; if (raw.length > 4000) return reject(413); }
        const input = JSON.parse(raw);
        if (url.pathname === '/api/learning/preferences') {
          if (input.expectedRevision !== fixture.preferences.revision) return reject(409);
          fixture.preferences = { personalizationEnabled: input.personalizationEnabled, revision: fixture.preferences.revision + 1 };
          return ok(fixture.preferences);
        }
        const match = /^\/api\/learning\/observations\/([a-f0-9]{64})\/(corrections|delete)$/.exec(url.pathname);
        const row = match && fixture.observations.find(r => r.id === match[1]);
        if (!row) return reject(404);
        if (row.revision !== input.expectedRevision) return reject(409);
        row.revision++; fixture.preferences.revision++; row.fact = null;
        if (match[2] === 'corrections') { row.status = 'CORRECTED'; row.correction = input.text; return ok(row); }
        fixture.observations = fixture.observations.filter(r => r.id !== row.id); fixture.total--; return ok(null);
      }
      return reject(404);
    }
    const pathname = resolve(dist, '.' + (extname(url.pathname) ? url.pathname : '/index.html'));
    if (!pathname.startsWith(dist + sep)) return reject(403);
    let contents = await readFile(pathname);
    if (pathname.endsWith('index.html')) {
      // 仅在本验收使用的本机源内写入合成身份。
      const bootstrap = `<script>localStorage.setItem('token','synthetic-memory-token');localStorage.setItem('user',JSON.stringify({id:1,username:'memory-fixture',displayName:'学习观察验收',targetRole:'Java',skillTags:[],demo:false}));</script>`;
      contents = Buffer.from(contents.toString().replace('<head>', '<head>' + bootstrap));
    }
    res.setHeader('Content-Type', ({ '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' })[extname(pathname)] || 'application/octet-stream');
    res.end(contents);
  } catch { reject(500); }
});
server.listen(15174, '127.0.0.1', () => console.log('Synthetic memory preview: http://127.0.0.1:15174/learning/memory (Ctrl+C stops; no persisted mutations)'));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
