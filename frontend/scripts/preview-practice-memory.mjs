// 生产界面回放隔离 MySQL 夹具，仅使用合成鉴权。
// 不访问开发数据源、供应商密钥、外部上传或持久浏览器修改。
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, join, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertPortFree } from '../../scripts/dev.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url));
const dist=join(root,'frontend/dist');
const session=JSON.parse(await readFile(join(root,'backend/target/practice-memory-session.json'),'utf8'));
const memory=JSON.parse(await readFile(join(root,'backend/target/learning-memory-fixture.json'),'utf8'));
await assertPortFree(15175);
const server=createServer(async(req,res)=>{
  const ok=data=>{res.setHeader('Content-Type','application/json; charset=utf-8');res.end(JSON.stringify({success:true,data,message:''}));};
  try {
    const url=new URL(req.url,'http://127.0.0.1:15175');
    if(url.pathname.startsWith('/api/')) {
      if(req.method!=='GET') return res.writeHead(405).end();
      if(url.pathname==='/api/interviews/sessions/'+session.sessionId) return ok(session);
      if(url.pathname==='/api/learning/observations') return ok(memory);
      if(url.pathname==='/api/interview-runs'||url.pathname==='/api/resumes'||url.pathname.endsWith('/resources')) return ok([]);
      if(['/api/interviews/sessions','/api/interviews/training-tasks','/api/target-jobs','/api/knowledge/documents'].includes(url.pathname)) return ok({items:[],total:0,page:1,size:20});
      return res.writeHead(404).end();
    }
    const path=resolve(dist,'.'+(extname(url.pathname)?url.pathname:'/index.html'));
    if(!path.startsWith(dist+sep)) return res.writeHead(403).end();
    let contents=await readFile(path);
    if(path.endsWith('index.html')) contents=Buffer.from(contents.toString().replace('<head>',`<head><script>localStorage.setItem('token','synthetic-practice-memory');localStorage.setItem('user',JSON.stringify({id:1,username:'practice-memory-fixture',displayName:'历史工具验收',targetRole:'Java',skillTags:[],demo:false}));</script>`));
    res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css'})[extname(path)]||'application/octet-stream');res.end(contents);
  } catch {res.writeHead(500).end();}
});
server.listen(15175,'127.0.0.1',()=>console.log(`Synthetic history preview: http://127.0.0.1:15175/interview?sessionId=${session.sessionId}`));
for(const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>server.close());
