// 生产界面只读回放隔离 MySQL 的合成夹具。
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertPortFree } from '../../scripts/dev.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = join(root, 'frontend/dist');
const session = JSON.parse(await readFile(join(root, 'backend/target/loop-run-session.json'), 'utf8'));
const trace = JSON.parse(await readFile(join(root, 'backend/target/loop-run-trace.json'), 'utf8'));
const run = JSON.parse(await readFile(join(root, 'backend/target/loop-run-summary.json'), 'utf8'));
const formal = { ...session, sessionId: 'loop-formal-fixture', agent: null,
  experience: { ...session.experience, format: 'FORMAL' },
  turns: session.turns.filter(t => !t.content.startsWith('即时反馈：')) };
await assertPortFree(15178);
const server = createServer(async (req, res) => {
  const ok = data => { res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify({ success: true, data, message: '' })); };
  try {
    const url = new URL(req.url, 'http://127.0.0.1:15178');
    if (url.pathname.startsWith('/api/')) {
      if (req.method !== 'GET') return res.writeHead(405).end();
      if (url.pathname === '/api/interviews/sessions/' + session.sessionId) return ok(session);
      if (url.pathname === '/api/interviews/sessions/' + formal.sessionId) return ok(formal);
      if (url.pathname === '/api/interviews/sessions/' + session.sessionId + '/runs') return ok([run]);
      if (url.pathname === '/api/interview-runs/' + run.runId + '/steps') return ok(trace);
      if (['/api/resumes', '/api/interview-runs'].includes(url.pathname) || url.pathname.endsWith('/resources')) return ok([]);
      if (['/api/interviews/sessions', '/api/interviews/training-tasks', '/api/target-jobs', '/api/knowledge/documents'].includes(url.pathname) || url.pathname.endsWith('/retries')) return ok({ items: [], total: 0, page: 1, size: 20 });
      return res.writeHead(404).end();
    }
    const path = resolve(dist, '.' + (extname(url.pathname) ? url.pathname : '/index.html'));
    if (!path.startsWith(dist + sep)) return res.writeHead(403).end();
    let contents = await readFile(path);
    if (path.endsWith('index.html')) contents = Buffer.from(contents.toString().replace('<head>', `<head><script>localStorage.setItem('token','synthetic-loop-fixture');localStorage.setItem('user',JSON.stringify({id:1,username:'loop-fixture',displayName:'运行过程验收',targetRole:'Java',skillTags:[],demo:false}));</script>`));
    res.setHeader('Content-Type', ({ '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' })[extname(path)] || 'application/octet-stream');
    res.end(contents);
  } catch { res.writeHead(500).end(); }
});
server.listen(15178, '127.0.0.1', () => console.log(`Loop fixture: http://127.0.0.1:15178/interview?sessionId=${session.sessionId}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
