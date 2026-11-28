import { verifyBackend } from './verify-backend.mjs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 每次中间件验收使用独立的临时 MySQL，定向回归跳过全项目覆盖率检查。
export function verifyMiddleware(args = process.argv.slice(2)) {
  const prefix = process.env.ROCKETMQ_RESOURCE_PREFIX;
  const build = /^it-[0-9a-f]{16}$/.test(prefix || '')
    ? ['-Dzhizhida.build.directory=target/middleware-live/' + prefix] : [];
  if (args.includes('--split-load')) {
    if (args.includes('--benchmark') || args.includes('--knowledge-only')) throw new Error('--split-load must run separately');
    if (process.env.RUN_MIDDLEWARE_TESTS !== 'true' || process.env.RUN_SPLIT_LOAD_TESTS !== 'true')
      throw new Error('Use verify-middleware-live.mjs --split-load with isolated middleware.');
    return verifyBackend(args.filter(arg => arg !== '--split-load'), [...build,
      '-Dtest=RuntimeRoleConfigurationTest,AiCallGuardTest', '-Dit.test=SplitRuntimeIT#compareWorkersAndReplayDurableProgressWithCancellation', '-Djacoco.skip=true',
    ]);
  }
  if (args.includes('--benchmark')) {
    if (args.includes('--knowledge-only')) throw new Error('--benchmark and --knowledge-only are mutually exclusive');
    if (process.env.RUN_MIDDLEWARE_TESTS !== 'true' || process.env.RUN_MIDDLEWARE_BENCHMARK !== 'true') {
      throw new Error('Benchmark requires RUN_MIDDLEWARE_TESTS=true and RUN_MIDDLEWARE_BENCHMARK=true; use verify-middleware-live.mjs --benchmark.');
    }
    return verifyBackend(args.filter(arg => arg !== '--benchmark'), [...build,
      '-Dtest=MiddlewareReadinessTest,RunDispatch*Test,InterviewRunWorkerTest,ModuleBoundaryTest', '-Dit.test=SplitRuntimeIT$Benchmark', '-Djacoco.skip=true',
    ]);
  }
  if(args.includes('--knowledge-only')) return verifyBackend(args.filter(arg=>arg!=='--knowledge-only'),[...build,
    '-Dtest=RuntimeRoleConfigurationTest,KnowledgeImportJobsTest,KnowledgeDocumentImporterTest,ModuleBoundaryTest',
    '-Dit.test=KnowledgeImportIT,KnowledgeIndexLifecycleIT,KnowledgeMqIT$SharedFiles,KnowledgeMqIT$Broker,SplitRuntimeIT#apiQueuesWithoutWorkerThenIndependentWorkerExecutesAndCanRestart',
    '-Djacoco.skip=true',
  ]);
  const unitTests = '-Dtest=QuestionSnapshotCacheTest,RedisSubmissionRateLimiterTest,RedisResourcesConfigurationTest,RuntimeRoleConfigurationTest,KnowledgeImportJobsTest,InterviewRunAdmissionTest,RunDispatch*Test,RunRecoverySchedulerTest,InterviewRunWorkerTest,MiddlewareLiveTest,MiddlewareReadinessTest,HealthControllerTest,ModuleBoundaryTest';
  const integrationGroups = [
    'InterviewRecoveryIT$DurableRuns,InterviewRecoveryIT$ConcurrentCommit,QuestionBankIntegrationIT,RequestQuotaIntegrationIT,InterviewAcceptanceIT$TrainingSources',
    'KnowledgeImportIT,KnowledgeIndexLifecycleIT,KnowledgeMqIT$SharedFiles,KnowledgeMqIT$Broker',
    'MiddlewareFailureIT,MiddlewareApplicationIT',
    'SplitRuntimeIT#apiQueuesWithoutWorkerThenIndependentWorkerExecutesAndCanRestart',
  ];
  const common = [...build, '-Djacoco.skip=true'];
  if (args.includes('--integration-only')) {
    return verifyBackend(args, [...common, unitTests, '-Dit.test=' + integrationGroups.join(',')]);
  }
  // 各组复用同一测试库，每组单独执行 Failsafe 验证并使用独立的五分钟时限。
  return verifyBackend(args, common, [
    [unitTests, 'package'],
    ...integrationGroups.map(group => ['-Dit.test=' + group, 'failsafe:integration-test', 'failsafe:verify']),
  ]);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verifyMiddleware().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
