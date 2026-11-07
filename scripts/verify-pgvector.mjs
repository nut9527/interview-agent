import {mkdir,mkdtemp,readFile,writeFile,stat,realpath,rm} from 'node:fs/promises';
import {join,sep} from 'node:path';
import {randomBytes,createHash} from 'node:crypto';
import {PROJECT_ROOT,capture,findExecutable,assertPortFree} from './dev.mjs';
const args=process.argv.slice(2);
if(args.includes('--help')) console.log('node scripts/verify-pgvector.mjs --docker\nExplicitly pulls pgvector/pgvector:pg17 and creates/removes one disposable loopback-only container. No application DB/.env; synthetic HTTP embedding.');
else {
  if(args.length!==1||args[0]!=='--docker')throw new Error('Explicit --docker required; use --help');
  const parent=join(PROJECT_ROOT,'.runtime');await mkdir(parent,{recursive:true});
  const temp=await mkdtemp(join(parent,'pgvector-acceptance-'));
  const name='agent-pgvector-'+randomBytes(8).toString('hex');
  const password=randomBytes(24).toString('hex'),port=15439,image='pgvector/pgvector:pg17';
  // Docker CLI 配置和临时文件均保存在项目内。
  const env={...process.env,TMP:temp,TEMP:temp,DOCKER_CONFIG:temp,POSTGRES_PASSWORD:password};
  const docker=findExecutable('docker');let owned=false;
  const command=async(command,timeout=30000)=>{
    const result=await capture(docker,command,{env,cwd:PROJECT_ROOT,timeout});
    if(result.code!==0)throw new Error('Docker command failed: '+command[0]);return result.output.trim();
  };
  try {
    await command(['version','--format','{{.Server.Version}}']);await assertPortFree(port);
    // 拉取超时仅重试一次，其他失败直接保留。
    for(let n=0;n<2;n++) {try{await command(['pull',image],240000);break;}catch(e){if(n||!/timed out|超时/i.test(e.message))throw e;}}
    const imageId=await command(['image','inspect',image,'--format','{{.Id}}']);
    await command(['create','--name',name,'--label','zhizhida.acceptance=pgvector','-e','POSTGRES_PASSWORD',
      '-e','POSTGRES_DB=agent_vector_test','-p',`127.0.0.1:${port}:5432`,'--tmpfs','/var/lib/postgresql/data',image]);owned=true;
    await command(['start',name]);
    let ready=false;
    for(let i=0;i<40;i++) {
      const ping=await capture(docker,['exec',name,'pg_isready','-U','postgres','-d','agent_vector_test'],{env,timeout:3000});
      if(ping.code===0){ready=true;break;}await new Promise(r=>setTimeout(r,500));
    }
    if(!ready)throw new Error('Disposable PostgreSQL readiness timeout');
    await command(['exec',name,'psql','-U','postgres','-d','agent_vector_test','-v','ON_ERROR_STOP=1','-c','CREATE EXTENSION vector']);
    const started=Date.now();
    const result=await capture(findExecutable('mvn'),['-o','-B','-ntp',`-Dmaven.repo.local=${join(PROJECT_ROOT,'.m2/repository')}`,
      `-Djava.io.tmpdir=${temp}`,'-Dtest=PgvectorAcceptanceTest','-Djacoco.skip=true','test'],
      {env:{...env,RUN_PGVECTOR_ACCEPTANCE:'true',PGVECTOR_TEST_PORT:String(port),PGVECTOR_TEST_PASSWORD:password},cwd:join(PROJECT_ROOT,'backend'),timeout:240000});
    const path=join(PROJECT_ROOT,'backend/target/pgvector-acceptance.json');
    if(await stat(path).then(s=>s.mtimeMs>=started).catch(()=>false)) {
      const report=JSON.parse(await readFile(path,'utf8'));report.image=image;report.imageId=imageId;report.sourceSha256={};
      delete report.recordedAt;
      for(const file of ['scripts/verify-pgvector.mjs','backend/src/test/java/cn/edu/whu/zhizhida/ai/PgvectorAcceptanceTest.java',
        'backend/src/main/java/cn/edu/whu/zhizhida/ai/LangChain4jKnowledgeVectors.java','backend/pom.xml'])
        report.sourceSha256[file]=createHash('sha256').update(await readFile(join(PROJECT_ROOT,file))).digest('hex');
      await writeFile(join(PROJECT_ROOT,'docs/evidence/pgvector-acceptance.json'),JSON.stringify(report,null,2)+'\n');
    }
    if(result.code!==0)throw new Error('pgvector test failed; inspect local Surefire report.');
    console.log('[pgvector] Real storage acceptance passed; embedding is a synthetic HTTP fixture.');
  } finally {
    if(owned)await command(['rm','--force','--volumes',name]);
    const actual=await realpath(temp),allowed=await realpath(parent);
    if(!actual.startsWith(allowed+sep))throw new Error('Unsafe cleanup path');
    await rm(actual,{recursive:true,force:false});
  }
}
