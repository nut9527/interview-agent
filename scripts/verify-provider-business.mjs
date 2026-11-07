import {readFile,writeFile,stat,mkdir,mkdtemp,rm,realpath} from 'node:fs/promises';
import {join,sep} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {PROJECT_ROOT,parseEnv,capture,findExecutable} from './dev.mjs';
import {summarizeBusiness} from './provider-business-metrics.mjs';
const args=process.argv.slice(2);
if(args.includes('--help')) {
  console.log('node scripts/verify-provider-business.mjs --preflight [--model=MODEL] [--windows-root]');
  console.log('node scripts/verify-provider-business.mjs --real --max-requests=4 --max-output-tokens=3000 [--model=MODEL] [--windows-root]');
  console.log('Model-list preflight precedes inference. Four synthetic JSON cases, 750 tokens/send. No retries; each report has a unique filename.');
} else {
  const overrides=args.filter(a=>a.startsWith('--model='));
  const windowsRoot=args.includes('--windows-root');
  if(args.filter(a=>a==='--windows-root').length>1 || (windowsRoot && process.platform!=='win32'))throw new Error('--windows-root requires Windows');
  const flags=args.filter(a=>!a.startsWith('--model=')&&a!=='--windows-root');
  const preflightOnly=flags.length===1&&flags[0]==='--preflight';
  if(overrides.length>1 || (overrides.length && !/^--model=[A-Za-z0-9._-]{1,100}$/.test(overrides[0])) ||
    (!preflightOnly && (flags.length!==3 || !flags.includes('--real') || !flags.includes('--max-requests=4') || !flags.includes('--max-output-tokens=3000'))))
    throw new Error('Explicit bounded real opt-in or --preflight required; use --help');
  const local=await readFile(join(PROJECT_ROOT,'.env'),'utf8').then(parseEnv).catch(e=>{if(e.code==='ENOENT')return {};throw e;});
  const config={...local,...process.env},key=config.AI_API_KEY||config.DEEPSEEK_API_KEY;
  if(!key)throw new Error('Missing provider key; no sends');
  const model=overrides[0]?.slice('--model='.length)||config.DEEPSEEK_MODEL||'deepseek-flash';
  const endpoint=config.DEEPSEEK_BASE_URL||'https://api.deepseek.com',uri=new URL(endpoint);
  if(uri.origin!=='https://api.deepseek.com'||uri.username||uri.password||uri.search||uri.hash||!['/','/v1','/v1/'].includes(uri.pathname))throw new Error('Official DeepSeek endpoint required');
  const parent=join(PROJECT_ROOT,'.runtime');await mkdir(parent,{recursive:true});
  const temp=await mkdtemp(join(parent,'business-acceptance-'));
  try {
    const env={...process.env,TMP:temp,TEMP:temp,AI_API_KEY:key,
      ACCEPTANCE_BASE_URL:endpoint,ACCEPTANCE_MODEL:model,
      BUSINESS_MAX_REQUESTS:'4',BUSINESS_MAX_OUTPUT_TOKENS:'3000'};
    const batch=randomUUID();
    const common=['-o','-B','-ntp',`-Dmaven.repo.local=${join(PROJECT_ROOT,'.m2/repository')}`,`-Djava.io.tmpdir=${temp}`,'-Djacoco.skip=true'];
    // 每个进程显式选择信任配置，证书和主机名校验仍启用。
    if(windowsRoot)common.push('-Djavax.net.ssl.trustStoreType=Windows-ROOT','-Djavax.net.ssl.trustStore=NONE');
    const fresh=async(path,since)=>await stat(path).then(s=>s.mtimeMs>=since).catch(()=>false);
    const discoveryStarted=Date.now();
    const discovery=await capture(findExecutable('mvn'),[...common,'-Dtest=ProviderModelPreflightTest#configuredModelMustBeListedBeforeInference','test'],
      {cwd:join(PROJECT_ROOT,'backend'),env:{...env,RUN_PROVIDER_PREFLIGHT:'true',RUN_PROVIDER_BUSINESS:'false'},timeout:120000});
    const discoveryPath=join(PROJECT_ROOT,'backend/target/provider-model-preflight.json');
    let preflight;
    if(await fresh(discoveryPath,discoveryStarted)) {
      preflight=JSON.parse(await readFile(discoveryPath,'utf8'));
      delete preflight.recordedAt;
      await writeFile(join(PROJECT_ROOT,`docs/evidence/provider-model-preflight-${batch}.json`),JSON.stringify(preflight,null,2)+'\n',{flag:'wx'});
    }
    if(discovery.code!==0 || preflight?.result!=='PASSED')throw new Error('Model preflight failed; zero inference requests sent. Inspect the new preflight evidence.');
    if(preflightOnly) {
      console.log('[business] Configured model is listed; zero inference requests sent.');
    } else {
    const started=Date.now(); let result,executionError;
    try { result=await capture(findExecutable('mvn'),[...common,'-Dtest=ProviderBusinessAcceptanceTest#realSyntheticBusinessAcceptance','test'],
      {cwd:join(PROJECT_ROOT,'backend'),env:{...env,RUN_PROVIDER_BUSINESS:'true'},timeout:240000}); }
    catch(error) { executionError=error; }
    const source=join(PROJECT_ROOT,'backend/target/provider-business-acceptance.json');
    if(await stat(source).then(s=>s.mtimeMs>=started).catch(()=>false)) {
      const report=JSON.parse(await readFile(source,'utf8'));
      delete report.recordedAt;
      report.summary=report.attempts?.length===4?summarizeBusiness(report):{releaseDecision:'INCOMPLETE',monetaryCost:null};
      report.result=report.attempts?.length===4&&report.attempts.every(a=>a.status==='RETURNED')?'COMPLETED':'FAILED_OR_PARTIAL';
      report.preflight=preflight;
      report.command='node scripts/verify-provider-business.mjs '+args.join(' ');
      report.sourceSha256={};
      for(const path of ['backend/src/test/resources/provider-business-cases.json',
        'backend/src/test/java/cn/edu/whu/zhizhida/ai/ProviderBusinessAcceptanceTest.java',
        'backend/src/test/java/cn/edu/whu/zhizhida/ai/ProviderModelPreflightTest.java',
        'backend/src/main/java/cn/edu/whu/zhizhida/ai/DeepSeekChatClient.java',
        'backend/src/main/java/cn/edu/whu/zhizhida/ai/LangChain4jExchange.java','backend/pom.xml',
        'scripts/verify-provider-business.mjs','scripts/provider-business-metrics.mjs'])
        report.sourceSha256[path]=createHash('sha256').update(await readFile(join(PROJECT_ROOT,path))).digest('hex');
      const destination=`docs/evidence/provider-business-acceptance-${batch}.json`;
      await writeFile(join(PROJECT_ROOT,destination),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
      console.log('[business] Evidence: '+destination);
      if(report.result!=='COMPLETED')throw new Error('Acceptance failed or is partial; failure evidence retained. No automatic paid retry.');
    } else throw new Error('No fresh complete evidence; possible sends/usage remain UNKNOWN; do not automatically retry.');
    if(executionError || result?.code!==0)throw new Error('Acceptance failed; see sanitized evidence. No automatic paid retry.');
    console.log('[business] Four attempts recorded; inspect quality proxies and unknown usage before interpreting results.');
    }
  } finally {
    const actual=await realpath(temp),allowed=await realpath(parent);
    if(!actual.startsWith(allowed+sep))throw new Error('Unsafe cleanup path');
    await rm(actual,{recursive:true,force:false});
  }
}
