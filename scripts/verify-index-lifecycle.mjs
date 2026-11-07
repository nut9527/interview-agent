import {readFile,writeFile,stat,readdir} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {PROJECT_ROOT} from './dev.mjs';
import {verifyBackend} from './verify-backend.mjs';
import {passingSuite} from './maven-test-report.mjs';
const args=process.argv.slice(2);
if(args.some(a=>!['--offline','--help'].includes(a)))throw new Error('Usage: node scripts/verify-index-lifecycle.mjs [--offline]');
if(args.includes('--help')) console.log('Isolated MySQL index lifecycle + LangChain4j contracts + offline business acceptance; no paid or pgvector calls.');
else {
  const unit=(await readdir(join(PROJECT_ROOT,'backend/src/test/java/cn/edu/whu/zhizhida/ai')))
    .filter(name=>/^(LangChain4j|DeepSeek).*Test\.java$/.test(name)).map(name=>'ai.'+name.slice(0,-5))
    .concat(['ai.ProviderBusinessAcceptanceTest','ai.ProviderModelPreflightTest','knowledge.HybridKnowledgeSearchTest','architecture.ModuleBoundaryTest']);
  const selectors=unit.map(name=>'cn.edu.whu.zhizhida.'+name+(name.endsWith('ProviderBusinessAcceptanceTest')
    ?'#offlineFixturePreservesFailuresUnknownUsageAndSendCaps':name.endsWith('ProviderModelPreflightTest')
    ?'#rejectsMissingMalformedAndUnavailableModels':''));
  let started;
  for(let attempt=0;attempt<2;attempt++) {
    started=Date.now();
    try {
      await verifyBackend(args,['-ntp',`-Dmaven.repo.local=${join(PROJECT_ROOT,'.m2/repository')}`,
        `-Dtest=${selectors.join(',')}`,
        '-Dit.test=KnowledgeIndexLifecycleIT','-Djacoco.skip=true']);break;
    } catch(e) {if(attempt||!/timed out|超时/i.test(e.message))throw e;}
  }
  const suites=[];
  for(const name of [...unit,'knowledge.KnowledgeIndexLifecycleIT']) {
    const path=join(PROJECT_ROOT,'backend/target',name.endsWith('IT')?'failsafe-reports':'surefire-reports',`TEST-cn.edu.whu.zhizhida.${name}.xml`);
    if((await stat(path)).mtimeMs<started)throw new Error('Stale evidence: '+name);
    suites.push({...passingSuite(await readFile(path,'utf8'),'cn.edu.whu.zhizhida.'+name),name});
  }
  const sourceSha256={};
  for(const path of ['backend/src/main/resources/db/migration/V46__knowledge_index_jobs.sql',
    ...['KnowledgeIndexRepository','KnowledgeIndexWorker','KnowledgeIndexController'].map(n=>`backend/src/main/java/cn/edu/whu/zhizhida/knowledge/${n}.java`),
    'backend/src/main/java/cn/edu/whu/zhizhida/ai/LangChain4jKnowledgeVectors.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/ai/api/KnowledgeVectors.java',
    'backend/src/main/resources/application.yml','backend/src/test/resources/provider-business-cases.json',
    ...suites.map(s=>`backend/src/test/java/cn/edu/whu/zhizhida/${s.name.replaceAll('.','/')}.java`),
    'scripts/verify-index-lifecycle.mjs','scripts/maven-test-report.mjs','scripts/verify-pgvector.mjs','scripts/verify-provider-business.mjs','scripts/provider-business-metrics.mjs'])
    sourceSha256[path]=createHash('sha256').update(await readFile(join(PROJECT_ROOT,path))).digest('hex');
  await writeFile(join(PROJECT_ROOT,'docs/evidence/index-lifecycle.json'),JSON.stringify({schemaVersion:1,
    command:`node scripts/verify-index-lifecycle.mjs${args.includes('--offline')?' --offline':''}`,paidCalls:0,realPgvectorTested:false,
    tests:suites.reduce((n,s)=>n+s.tests,0),suites,sourceSha256,
    scope:'isolated MySQL job lifecycle, synthetic vector adapter contracts and business acceptance harness',
    separateEvidence:{pgvector:'docs/evidence/pgvector-acceptance.json',businessDiscovery:'docs/evidence/provider-business-diagnostic.json'},
    excluded:['real provider quality and cost','live embedding service and pgvector','full coverage gate']},null,2)+'\n');
  console.log('[index] Lifecycle and offline acceptance verified; real provider and pgvector results are separate evidence.');
}
