import { readFile, writeFile, stat, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { PROJECT_ROOT } from './dev.mjs';
import { verifyBackend } from './verify-backend.mjs';
import { testVersions } from './langchain4j-test-versions.mjs';
import { passingSuite } from './maven-test-report.mjs';

// 仅使用本机合成 HTTP 和项目内一次性 MySQL，不读取 .env。
const args = process.argv.slice(2);
if (args.some(arg => !['--offline', '--help'].includes(arg))) throw new Error('Usage: node scripts/verify-langchain4j-runtime.mjs [--offline]');
if (args.includes('--help')) {
  console.log('Verify LangChain4j JSON/Agent calls, durable recovery, bounded JSON transport and tracing.');
} else {
  const aiTests = (await readdir(join(PROJECT_ROOT, 'backend/src/test/java/cn/edu/whu/zhizhida/ai')))
    .filter(name => /^(LangChain4j|DeepSeek).*Test\.java$/.test(name))
    .map(name => `ai.${name.slice(0, -5)}`);
  const unit = [...aiTests, 'architecture.ModuleBoundaryTest'];
  const integration = ['interview.InterviewModelToolsIT$ModelTransport', 'interview.InterviewStreamingTracingIT$Tracing'];
  const attempts = [];
  let started;
  for (let attempt = 1; attempt <= 2; attempt++) {
    started = Date.now();
    try {
      await verifyBackend(args, ['-ntp', `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`,
        `-Dtest=${unit.map(name => `cn.edu.whu.zhizhida.${name}`).join(',')}`,
        `-Dit.test=${integration.map(name => `cn.edu.whu.zhizhida.${name}`).join(',')}`,
        '-Djacoco.skip=true']);
      attempts.push({ attempt, result: 'passed' });
      break;
    } catch (error) {
      const timeout = /超时|timed out/i.test(error.message);
      attempts.push({ attempt, result: timeout ? 'timeout' : 'failed' });
      if (!timeout || attempt === 2) throw error;
      console.log('[langchain4j-runtime] Timed out; owned processes were stopped. Retrying once.');
    }
  }
  const suites = [];
  for (const [directory, names] of [
    ['surefire-reports', unit],
    ['failsafe-reports', integration]
  ]) {
    const folder = join(PROJECT_ROOT, 'backend/target', directory);
    for (const suite of names.sort()) {
      const name = `TEST-cn.edu.whu.zhizhida.${suite.split('$')[0]}.xml`;
      const path = join(folder, name);
      if ((await stat(path)).mtimeMs < started) throw new Error(`Stale report: ${name}`);
      const xml = await readFile(path, 'utf8');
      suites.push(passingSuite(xml, `cn.edu.whu.zhizhida.${suite}`));
    }
  }
  const totals = suites.reduce((sum, suite) => Object.fromEntries(Object.keys(sum).map(key => [key, sum[key] + suite[key]])),
    { tests: 0, failures: 0, errors: 0, skipped: 0 });
  const hashes = {};
  const ai = 'backend/src/main/java/cn/edu/whu/zhizhida/ai';
  const files = ['backend/pom.xml', 'backend/.mvn/project-parent/pom.xml', 'backend/src/main/resources/application.yml',
    'scripts/langchain4j-test-versions.mjs', 'scripts/maven-test-report.mjs',
    'scripts/verify-langchain4j-runtime.mjs', '.env.example', 'docker-compose.yml',
    ...(await readdir(join(PROJECT_ROOT, ai))).filter(name => /^(LangChain4j|DeepSeek).*\.java$/.test(name)).map(name => `${ai}/${name}`),
    ...suites.map(suite => `backend/src/test/java/${suite.name.split('$')[0].replaceAll('.', '/')}.java`)];
  for (const file of files) hashes[file] = createHash('sha256').update(await readFile(join(PROJECT_ROOT, file))).digest('hex');
  const evidence = {
    schemaVersion: 1, batch: 'langchain4j-runtime',
    scope: 'JSON and durable Agent model calls; synthetic HTTP and disposable MySQL',
    command: `node scripts/verify-langchain4j-runtime.mjs${args.includes('--offline') ? ' --offline' : ''}`,
    versions: testVersions(await readFile(join(PROJECT_ROOT, 'backend/target/surefire-reports',
      'TEST-cn.edu.whu.zhizhida.ai.DeepSeekTransportTest.xml'), 'utf8')),
    attempts, totals, suites, sourceSha256: hashes,
    verified: ['bounded JSON before decoding',
      'total-body deadline and server-observed socket closure', 'raw usage/history/schema preservation',
      'one HTTP send per committed Attempt, no SDK retries or redirects',
      'durable tool receipt/retry recovery and saved FINAL without another HTTP call',
      'JSON cancellation, truncated response and deadline rejection',
      'SDK types confined to model adapter',
      'single application Jackson 3 mapper and frozen adapter fingerprint', 'shared metrics without double counting',
      'HTTP model spans match durable Attempt IDs and retain Worker parent across virtual threads'],
    modelSdk: 'langchain4j', paidCalls: 0,
    fullCoverageGateRun: false,
    excluded: ['real-provider quality comparison', 'running service restart']
  };
  await writeFile(join(PROJECT_ROOT, 'docs/evidence/agent-langchain4j-runtime.json'), JSON.stringify(evidence, null, 2) + '\n');
  console.log(`[langchain4j-runtime] ${totals.tests} passed.`);
}
