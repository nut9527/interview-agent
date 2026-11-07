import { awaitInterviewRun, answerInterview } from './interview-run-client.mjs';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { liveStack } from './live-stack.mjs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const stack=await liveStack(); let browser;
const checks=[], questions=[];
try {
  const api=async(path,body,token,method=body?'POST':'GET',status=200)=>{
    const r=await fetch(stack.origin+'/api'+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(120000)});
    const data=await r.json(); assert.equal(r.status,status,JSON.stringify(data));return data.data;
  };
  const auth=await api('/auth/register',{username:'kb_'+Date.now(),password:'Acceptance123!',displayName:'知识库真实联调'});
  const other=await api('/auth/register',{username:'other_'+Date.now(),password:'Acceptance123!',displayName:'另一账号'});
  browser=await chromium.launchPersistentContext(stack.dir+'/browser',{channel:'msedge',headless:true,env:{...process.env,TMP:stack.tmp,TEMP:stack.tmp},viewport:{width:1360,height:1000}});
  const page=await browser.newPage(); page.setDefaultTimeout(20000);
  await page.goto(stack.origin+'/login');
  await page.evaluate(a=>{localStorage.setItem('token',a.token);localStorage.setItem('user',JSON.stringify(a.user));},auth);
  await page.goto(stack.origin+'/knowledge');
  const parsed=page.waitForResponse(r=>r.url().endsWith('/api/knowledge/imports/jobs'));
  const notes=await readFile(new URL('./fixtures/knowledge-notes.md',import.meta.url));
  await page.getByLabel('上传资料文件').setInputFiles({name:'星桥缓存.md',mimeType:'text/markdown',buffer:notes});
  await parsed;
  await page.getByRole('checkbox', { name: '我已核对文序、文字与完整性，确认以上正文入库' }).check();
  const uploaded=page.waitForResponse(r=>r.url().includes('/api/knowledge/imports/jobs/') && r.url().endsWith('/confirm'));
  await page.getByRole('button', { name: '确认入库为 v1', exact: true }).click();
  const doc=(await (await uploaded).json()).data;assert.ok(doc.id);
  await page.getByRole('heading',{name:doc.title,exact:true}).waitFor();
  await page.getByRole('button',{name:'重命名',exact:true}).click();
  await page.getByRole('textbox',{name:'资料标题'}).fill('星桥实验');
  await page.getByRole('dialog').getByRole('button',{name:'保存',exact:true}).click();
  await page.getByRole('heading',{name:'星桥实验',exact:true}).waitFor();
  await page.getByLabel('搜索资料',{exact:true}).fill('随机抖动');await page.getByRole('button',{name:'搜索',exact:true}).click();
  await page.getByRole('region',{name:'搜索结果'}).getByRole('link').first().click();
  const chunk=new URL(page.url()).searchParams.get('chunk'); assert.ok(chunk);
  await page.locator('[id="source-'+chunk+'"]').waitFor();
  assert.match(await page.locator('[id="source-'+chunk+'"]').innerText(),/90 秒/);
  checks.push('真实浏览器上传、列表、重命名、关键词搜索和点击原文定位');
  const distractor=await api('/knowledge/documents',{title:'其他实验',content:'# 其他实验\n\n另一个实验的缓存 TTL 是 600 秒，和星桥实验无关。'},auth.token);
  const cases=[['星桥实验缓存 TTL 的基准值和随机抖动是多少？',true,['90','30']],['空值缓存保存多久，创建商品后如何处理？',true,['12','删除']],['订单写后读从哪个库读取？',true,['主库']],['星桥实验的 Redis 内存上限是多少？',false,[]],['火星半径是多少？',false,[]]];
  for(const [question,sufficient,expected] of cases) {
    const answer=await api('/knowledge/answers',{question,documentIds:[doc.id]},auth.token);
    assert.equal(answer.sufficient,sufficient,question+': '+JSON.stringify(answer));
    const text=JSON.stringify(answer.claims);for(const term of expected)assert.ok(text.includes(term),text);
    for(const claim of answer.claims) for(const source of claim.citations) { assert.equal(source.documentId,doc.id); assert.ok(notes.toString().includes(source.quote)); }
    questions.push({question,answer});console.log('fixed question:',question,sufficient?'supported':'insufficient');
  }
  await page.getByRole('textbox',{name:'资料问题',exact:true}).fill(cases[0][0]);
  await page.getByRole('button',{name:'检索并回答',exact:true}).click();
  const panel=page.getByRole('region',{name:'基于资料问答'});
  await panel.getByRole('link').first().waitFor({timeout:120000});await panel.getByRole('link').first().click();
  assert.ok(new URL(page.url()).searchParams.get('chunk'));
  await page.screenshot({path:new URL('../docs/evidence/knowledge-desktop.png',import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/,'')});
  await page.getByRole('button',{name:'使用这份资料进入专项训练'}).click();
  await page.getByRole('button',{name:'开始专项训练',exact:true}).waitFor();
  const started=page.waitForResponse(r=>r.url().endsWith('/api/interviews/start-commands')&&r.request().method()==='POST',{timeout:120000});
  await page.getByRole('button',{name:'开始专项训练',exact:true}).click();
  const start=(await (await started).json());assert.ok(start.success,JSON.stringify(start));
  const session=await awaitInterviewRun(stack.origin,auth.token,start.data);assert.equal(session.agent.training.knowledge[0].content,notes.toString());
  assert.match(session.turns[1].content,/星桥实验/);
  assert.match(session.turns[1].content,/缓存|主库|TTL|空值/);
  // 通过真实 HTTP API，在浏览器创建的会话中完成三轮回答。
  // 默认配置为六轮，完成全部轮次以覆盖上下文续接。
  let completed=session;
  for(let round=1;round<=session.totalRounds;round++) {
    completed=await answerInterview(stack.origin,auth.token,session.sessionId,{answer:'星桥实验使用 90 秒 TTL 加 0～30 秒随机抖动。不存在的商品空值缓存 12 秒，创建后删除空值标记。订单写后从主库读取，仅保证本次请求。笔记没有压测结果，我会测量缓存命中率与过期时刻的数据库请求量，再比较变化。',expectedTurn:round,requestId:'kb-live-'+round});
    console.log('knowledge training round',round);
  }
  await page.reload();await page.getByText('本场知识库版本快照（1 份）').click();
  await page.screenshot({path:new URL('../docs/evidence/knowledge-training.png',import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/,'')});
  checks.push('资料问答展示引用并定位原文；从资料进入真实模型专项训练并完成全部轮次');
  await page.goto(stack.origin+'/knowledge?document='+doc.id);
  await page.setViewportSize({width:390,height:844});
  await page.waitForFunction(() => document.querySelector('.ant-layout-sider')?.getBoundingClientRect().width === 64);
  await page.getByRole('heading',{name:'星桥实验',exact:true}).waitFor();
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.screenshot({path:new URL('../docs/evidence/knowledge-mobile.png',import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/,'')});
  await page.getByRole('button',{name:'删除资料',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'确认删除',exact:true}).click();
  await page.getByRole('heading',{name:'星桥实验',exact:true}).waitFor({state:'hidden'});
  assert.equal((await api('/knowledge/search?query='+encodeURIComponent('随机抖动'),null,auth.token)).length,0);
  await api('/knowledge/documents/'+doc.id,null,auth.token,'GET',404);
  const reopened=await api('/interviews/sessions/'+session.sessionId,null,auth.token);assert.deepEqual(reopened.agent.training.knowledge,session.agent.training.knowledge);
  await api('/knowledge/documents/'+distractor.id,null,other.token,'GET',404);
  await api('/interviews/sessions/'+session.sessionId,null,other.token,'GET',404);
  await page.evaluate(a=>{localStorage.setItem('token',a.token);localStorage.setItem('user',JSON.stringify(a.user));},other);
  await page.goto(stack.origin+'/knowledge?document='+distractor.id);
  await page.getByText('资料不存在',{exact:true}).waitFor();
  assert.equal(await page.getByRole('heading',{name:'其他实验',exact:true}).count(),0);
  checks.push('删除后搜索与直接读取消失，历史训练快照仍可核对；切换账号无法读取他人文档或训练');
  await writeFile(new URL('../docs/evidence/knowledge-live.json',import.meta.url),JSON.stringify({stack:'React/Vite → Spring Boot HTTP → disposable MySQL → configured DeepSeek; no synthetic API',checks,questions,training:completed},null,2));
} catch (error) {
  console.error((await readFile(stack.dir+'/backend.log','utf8')).split('\n').slice(-18).join('\n'));
  throw error;
} finally { await browser?.close();await stack.cleanup(); }
