import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { PROJECT_ROOT } from './dev.mjs';
import { verifyBackend } from './verify-backend.mjs';
import { passingSuite } from './maven-test-report.mjs';

const args = process.argv.slice(2);
if (args.some(a => !['--offline', '--help'].includes(a))) throw new Error('Usage: node scripts/verify-agent-ecosystem.mjs [--offline]');
if (args.includes('--help')) {
  console.log('Synthetic JSON/MCP/vector contracts plus durable Worker checks on disposable MySQL; no paid requests.');
} else {
  const unit = (await readdir(join(PROJECT_ROOT, 'backend/src/test/java/cn/edu/whu/zhizhida/ai')))
    .filter(name => /^(LangChain4j|DeepSeek).*Test\.java$/.test(name)).map(name => `ai.${name.slice(0, -5)}`)
    .concat(['knowledge.HybridKnowledgeSearchTest', 'architecture.ModuleBoundaryTest']);
  const integration = ['InterviewModelToolsIT$ModelTransport', 'InterviewStreamingTracingIT$Tracing', 'InterviewModelToolsIT$Tools', 'InterviewModelToolsIT$Mcp']
    .map(name => `interview.${name}`);
  let started;
  for (let attempt = 1; attempt <= 2; attempt++) {
    started = Date.now();
    try {
      await verifyBackend(args, ['-ntp', `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`,
        `-Dtest=${unit.map(name => `cn.edu.whu.zhizhida.${name}`).join(',')}`,
        `-Dit.test=${integration.map(name => `cn.edu.whu.zhizhida.${name}`).join(',')}`,
        '-Djacoco.skip=true']);
      break;
    } catch (error) { if (!/timed out|超时/i.test(error.message) || attempt === 2) throw error; }
  }
  const suites = [];
  for (const [directory, names] of [['surefire-reports', unit], ['failsafe-reports', integration]]) {
    const folder = join(PROJECT_ROOT, 'backend/target', directory);
    for (const suite of names) {
      const name = `TEST-cn.edu.whu.zhizhida.${suite.split('$')[0]}.xml`;
      const path = join(folder, name);
      if ((await stat(path)).mtimeMs < started) throw new Error(`Stale report: ${name}`);
      suites.push(passingSuite(await readFile(path, 'utf8'), `cn.edu.whu.zhizhida.${suite}`));
    }
  }
  const hashes = {};
  const ai = 'backend/src/main/java/cn/edu/whu/zhizhida/ai';
  for (const path of ['backend/pom.xml', 'scripts/verify-agent-ecosystem.mjs', 'scripts/maven-test-report.mjs',
    ...['knowledge/HybridKnowledgeSearch.java', 'knowledge/TrainingKnowledgeCatalog.java', 'interview/execution/McpReferenceToolProvider.java',
      'interview/execution/LocalEvidenceToolProvider.java'].map(p => `backend/src/main/java/cn/edu/whu/zhizhida/${p}`),
    ...(await readdir(join(PROJECT_ROOT, ai))).filter(p => p.endsWith('.java')).map(p => `${ai}/${p}`),
    ...suites.map(s => `backend/src/test/java/${s.name.split('$')[0].replaceAll('.', '/')}.java`)])
    hashes[path] = createHash('sha256').update(await readFile(join(PROJECT_ROOT, path))).digest('hex');
  await writeFile(join(PROJECT_ROOT, 'docs/evidence/agent-ecosystem.json'), JSON.stringify({
    schemaVersion: 1, command: `node scripts/verify-agent-ecosystem.mjs${args.includes('--offline') ? ' --offline' : ''}`,
    paidCalls: 0, realPgvectorTested: false, modelSdk: 'langchain4j', mcpServiceUnderTest: 'synthetic-fixture', vectorEnabledByDefault: false,
    tests: suites.reduce((n, s) => n + s.tests, 0), suites, sourceSha256: hashes,
    excluded: ['real provider quality and cost', 'live embedding and pgvector services', 'full coverage gate'],
    separateChecks: ['scripts/verify-mcp-reference-live.mjs', 'scripts/verify-index-lifecycle.mjs', 'scripts/verify-pgvector.mjs']
  }, null, 2) + '\n');
  console.log(`[ecosystem] ${suites.reduce((n, s) => n + s.tests, 0)} tests passed; paid and PostgreSQL acceptance remain separate.`);
}
