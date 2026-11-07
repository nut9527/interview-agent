import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join, relative } from 'node:path';
import { PROJECT_ROOT, capture, findExecutable } from './dev.mjs';
import { testVersions } from './langchain4j-test-versions.mjs';
import { passingSuite } from './maven-test-report.mjs';

// 独立本机兼容门禁，不读取 .env，不启动数据库或供应商请求。
const args = process.argv.slice(2);
if (args.some(arg => !['--offline', '--help'].includes(arg))) throw new Error('Usage: node scripts/verify-langchain4j.mjs [--offline]');
if (args.includes('--help')) {
  console.log('Run isolated LangChain4j tests and record dependency/source fingerprints. Dependencies stay in project .m2.');
} else {
  const moduleDir = join(PROJECT_ROOT, 'backend/compatibility/langchain4j');
  const target = join(moduleDir, 'target');
  await mkdir(target, { recursive: true });
  const env = { ...process.env, TEMP: target, TMP: target,
    MAVEN_OPTS: `${process.env.MAVEN_OPTS || ''} -Djava.io.tmpdir="${target}" -XX:-UsePerfData` };
  for (const key of Object.keys(env)) {
    if (/^(AI_|DEEPSEEK_|OPENAI_|ANTHROPIC_|SPRING_|RUN_REAL_AGENT_EVAL)/.test(key)) delete env[key];
  }
  const command = ['-B', '-ntp', ...(args.includes('--offline') ? ['-o'] : []),
    `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`, 'test', 'dependency:tree', '-DoutputFile=target/dependency-tree.txt'];
  let result;
  let started;
  const attempts = [];
  const heartbeat = setInterval(() => console.log('[langchain4j] Local compatibility tests are running.'), 30000);
  try {
    for (let attempt = 1; attempt <= 2; attempt++) {
      started = Date.now();
      try {
        result = await capture(findExecutable('mvn'), command, { cwd: moduleDir, env, timeout: 300000 });
        attempts.push({ attempt, exitCode: result.code });
        break;
      } catch (error) {
        attempts.push({ attempt, timeout: /超时/.test(error.message) });
        if (!/超时/.test(error.message) || attempt === 2) throw error;
        console.log('[langchain4j] Five-minute timeout; stopped the process tree. Retrying once.');
      }
    }
  } finally { clearInterval(heartbeat); }
  console.log(result.output);
  if (result.code !== 0) throw new Error(`LangChain4j validation failed (${result.code}); inspect module target/surefire-reports. Evidence not replaced.`);

  const reportDir = join(target, 'surefire-reports');
  const testSources = await readdir(join(moduleDir, 'src/test/java/compatibility'));
  const expectedSuites = testSources.filter(name => name.endsWith('Test.java')).sort()
    .map(name => `compatibility.${name.slice(0, -5)}`);
  const suites = [];
  for (const suite of expectedSuites) {
    const name = `TEST-${suite}.xml`;
    const path = join(reportDir, name);
    if ((await stat(path)).mtimeMs < started) throw new Error(`Stale test report: ${name}`);
    const xml = await readFile(path, 'utf8');
    suites.push(passingSuite(xml, suite));
  }
  const totals = suites.reduce((sum, suite) => Object.fromEntries(Object.keys(sum).map(key => [key, sum[key] + suite[key]])),
    { tests: 0, failures: 0, errors: 0, skipped: 0 });
  if (suites.length < 5 || totals.tests < 45)
    throw new Error('Expected at least five compatibility suites and 45 tests; evidence not replaced.');
  const hashes = {};
  const sources = [join(moduleDir, 'pom.xml'), join(PROJECT_ROOT, 'backend/.mvn/project-parent/pom.xml'),
    join(PROJECT_ROOT, 'scripts/langchain4j-test-versions.mjs'), join(PROJECT_ROOT, 'scripts/verify-langchain4j.mjs'),
    join(PROJECT_ROOT, 'scripts/maven-test-report.mjs'), ...testSources.filter(name => name.endsWith('.java'))
      .map(name => join(moduleDir, 'src/test/java/compatibility', name))];
  for (const path of sources) hashes[relative(PROJECT_ROOT, path).replaceAll('\\', '/')] =
    createHash('sha256').update(await readFile(path)).digest('hex');
  const dependencyTree = join(target, 'dependency-tree.txt');
  if ((await stat(dependencyTree)).mtimeMs < started) throw new Error('Stale dependency tree; evidence not replaced.');
  const dependencies = (await readFile(dependencyTree, 'utf8')).trim().split(/\r?\n/);
  const evidence = {
    schemaVersion: 1, batch: 'M0-LC',
    scope: 'isolated-local-compatibility-and-boundary-prototypes',
    versions: testVersions(await readFile(join(reportDir, 'TEST-compatibility.LangChain4jBootTest.xml'), 'utf8')),
    command: `node scripts/verify-langchain4j.mjs${args.includes('--offline') ? ' --offline' : ''}`, attempts, totals, suites, dependencies, sourceSha256: hashes,
    reproducedSdkLimitations: [
      'Typed continuation drops unknown assistant fields; raw HTTP metadata retains them.',
      'Default ToolSpecification mapping drops additionalProperties=false.',
      'Typed usage coerces numeric strings; raw boundary classifies INVALID before conversion.',
      'EOF without DONE/finish is accepted; response metadata omits DONE.',
      'JDK model timeout does not cover a stalled response body after headers.'
    ],
    testedPrototypeFixes: [
      'Frozen raw message/schema projection and one-send synthetic admission at HTTP SPI.',
      'Raw usage classification and strict DONE plus successful finish at SSE boundary.',
      'Async JSON future/reactive SSE cancellation with total deadline; server observes socket closure.',
      'Caller interruption cancels the underlying JSON body and preserves the interrupt flag.'
    ],
    excluded: ['application Worker and persistence', 'real provider quality and cost'],
    paidCalls: 0, databaseAccess: false
  };
  const output = join(PROJECT_ROOT, 'docs/evidence/agent-langchain4j-compatibility.json');
  await writeFile(output, JSON.stringify(evidence, null, 2) + '\n');
  console.log(`[langchain4j] ${totals.tests} isolated compatibility tests passed; evidence: ${output}`);
}
