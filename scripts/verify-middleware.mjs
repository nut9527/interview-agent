import { verifyBackend } from './verify-backend.mjs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 沿用独占临时 MySQL、5 分钟超时及清理；定向回归不计算全项目覆盖率门禁。
export function verifyMiddleware(args = process.argv.slice(2)) {
  if (args.includes('--split-load')) {
    if (args.includes('--benchmark') || args.includes('--knowledge-only')) throw new Error('--split-load must run separately');
    if (process.env.RUN_MIDDLEWARE_TESTS !== 'true' || process.env.RUN_SPLIT_LOAD_TESTS !== 'true')
      throw new Error('Use verify-middleware-live.mjs --split-load with isolated middleware.');
    return verifyBackend(args.filter(arg => arg !== '--split-load'), [
      '-Dtest=RuntimeRoleConfigurationTest,AiCallGuardTest', '-Dit.test=SplitRuntimeIT#compareWorkersAndReplayDurableProgressWithCancellation', '-Djacoco.skip=true',
    ]);
  }
  if (args.includes('--benchmark')) {
    if (args.includes('--knowledge-only')) throw new Error('--benchmark and --knowledge-only are mutually exclusive');
    if (process.env.RUN_MIDDLEWARE_TESTS !== 'true' || process.env.RUN_MIDDLEWARE_BENCHMARK !== 'true') {
      throw new Error('Benchmark requires RUN_MIDDLEWARE_TESTS=true and RUN_MIDDLEWARE_BENCHMARK=true; use verify-middleware-live.mjs --benchmark.');
    }
    return verifyBackend(args.filter(arg => arg !== '--benchmark'), [
      '-Dtest=MiddlewareReadinessTest,RunDispatch*Test,InterviewRunWorkerTest,ModuleBoundaryTest', '-Dit.test=InterviewRecoveryIT$ConcurrentCommit,MiddlewareApplicationIT,SplitRuntimeIT$Benchmark', '-Djacoco.skip=true',
    ]);
  }
  if(args.includes('--knowledge-only')) return verifyBackend(args.filter(arg=>arg!=='--knowledge-only'),[
    '-Dtest=RuntimeRoleConfigurationTest,KnowledgeImportJobsTest,KnowledgeDocumentImporterTest,ModuleBoundaryTest',
    '-Dit.test=KnowledgeImportIT,KnowledgeIndexLifecycleIT,KnowledgeMqIT$SharedFiles,KnowledgeMqIT$Broker,SplitRuntimeIT#apiQueuesWithoutWorkerThenIndependentWorkerExecutesAndCanRestart',
    '-Djacoco.skip=true',
  ]);
  return verifyBackend(args, [
  '-Dtest=QuestionSnapshotCacheTest,RedisSubmissionRateLimiterTest,RedisResourcesConfigurationTest,RuntimeRoleConfigurationTest,KnowledgeImportJobsTest,InterviewRunAdmissionTest,RunDispatch*Test,RunRecoverySchedulerTest,InterviewRunWorkerTest,MiddlewareLiveTest,MiddlewareReadinessTest,HealthControllerTest,ModuleBoundaryTest',
  '-Dit.test=InterviewRecoveryIT$DurableRuns,InterviewRecoveryIT$ConcurrentCommit,QuestionBankIntegrationIT,RequestQuotaIntegrationIT,InterviewAcceptanceIT$TrainingSources,KnowledgeImportIT,KnowledgeIndexLifecycleIT,KnowledgeMqIT$SharedFiles,KnowledgeMqIT$Broker,MiddlewareFailureIT,MiddlewareApplicationIT,SplitRuntimeIT#apiQueuesWithoutWorkerThenIndependentWorkerExecutesAndCanRestart',
  '-Djacoco.skip=true',
  ]);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verifyMiddleware().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
