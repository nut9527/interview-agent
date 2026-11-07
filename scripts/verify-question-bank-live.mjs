import { awaitInterviewRun } from './interview-run-client.mjs';
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { liveStack } from './live-stack.mjs';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const stack = await liveStack();
let browser;
const samples=[];
try {
  const api=async(path,body,token)=>{
    const r=await fetch(stack.origin+'/api'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(120000)});
    const json=await r.json(); assert.equal(r.status,200,JSON.stringify(json)); return json.data;
  };
  const auth=await api('/auth/register',{username:'live_'+Date.now(),password:'Acceptance123!',displayName:'真实联调'});
  browser=await chromium.launchPersistentContext(stack.dir+'/browser',{channel:'msedge',headless:true,env:{...process.env,TMP:stack.tmp,TEMP:stack.tmp},viewport:{width:1280,height:960}});
  const page=await browser.newPage(); page.setDefaultTimeout(20000);
  await page.goto(stack.origin+'/login');
  await page.evaluate(a=>{localStorage.setItem('token',a.token);localStorage.setItem('user',JSON.stringify(a.user));},auth);
  for(const [index,id] of ['qb-java-001','qb-database-003','qb-redis-006','qb-concurrency-003','qb-performance-006','qb-reliability-001'].entries()) {
    const question=await api('/questions/'+id,null,auth.token);
    await page.goto(stack.origin+'/questions/'+id);
    await page.getByRole('button',{name:'练习这道题',exact:true}).click();
    const response=page.waitForResponse(r=>r.url().endsWith('/api/interviews/start-commands')&&r.request().method()==='POST',{timeout:120000});
    await page.getByRole('dialog').getByRole('button',{name:'进入训练室'}).click();
    const started=await (await response).json(); assert.equal(started.success,true,JSON.stringify(started));
    let session=await awaitInterviewRun(stack.origin,auth.token,started.data);
    assert.equal(session.turns[1].content,question.question);
    const answer=index===5?'我认为只要重试就一定能恢复，不需要做任何演练。':question.referenceAnswer;
    const rounds=index===0?3:1;
    for(let round=1;round<=rounds;round++) {
      await page.getByRole('textbox',{name:'面试回答'}).fill(answer);
      const feedback=page.waitForResponse(r=>r.url().includes('/answer-commands')&&r.request().method()==='POST',{timeout:120000});
      await page.getByRole('button',{name:/提交回答/}).click();
      const result=await (await feedback).json(); assert.equal(result.success,true,JSON.stringify(result)); session=await awaitInterviewRun(stack.origin,auth.token,result.data);
      const evaluation=session.agent.evaluations.at(-1); assert.equal(session.agent.executionMode,'model');
      assert.ok(!evaluation.decision.evidence || answer.includes(evaluation.decision.evidence));
      assert.ok(evaluation.decision.gap && evaluation.decision.practice);
      console.log('real model:',id,'round',round,evaluation.decision.level);
    }
    if(rounds===3) await page.getByRole('heading',{name:'回答证据与练习建议'}).waitFor();
    samples.push({id,topic:question.topic,difficulty:question.difficulty,question:question.question,answer,session});
  }
  await page.screenshot({path:new URL('../docs/evidence/question-bank-live.png',import.meta.url).pathname.replace(/^\/(?=[A-Z]:)/,'')});
  await writeFile(new URL('../docs/evidence/question-bank-live.json',import.meta.url),JSON.stringify({stack:'React/Vite → Spring Boot HTTP → disposable MySQL → configured DeepSeek; no API interception',samples},null,2));
} finally { await browser?.close(); await stack.cleanup(); }
