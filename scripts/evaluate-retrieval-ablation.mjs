// 2x2 retrieval ablation. Only loopback synthetic inputs; no .env, DB or answer-model calls.
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile, realpath, rm, open } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join, resolve, sep, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertPortFree, capture, findExecutable, stopChild } from './dev.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('Usage: node scripts/evaluate-retrieval-ablation.mjs\nOffline Maven cache required. Starts a private local BGE service on port 18093, runs four controlled ranking variants, writes docs/evidence/retrieval-ablation.json and cleans its temporary files/process.');
  process.exit(0);
}
if (args.length) throw new Error('Use --help; this evaluator always uses offline Maven and local BGE.');

const runtimeRoot = join(root, '.runtime');
await mkdir(runtimeRoot, { recursive: true });
const runDir = await mkdtemp(join(runtimeRoot, 'retrieval-ablation-'));
const port = 18093;
const env = { ...process.env, TMP: runDir, TEMP: runDir,
  DJL_CACHE_DIR: join(runDir, 'native'), ENGINE_CACHE_DIR: join(runDir, 'native'),
  MAVEN_OPTS: `-Djava.io.tmpdir=${runDir} -XX:-UsePerfData` };
const controller = new AbortController();
const interrupt = () => controller.abort();
process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
let server, serverLog;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function stage(label, command, commandArgs, cwd = root) {
  // A stalled step is terminated at five minutes and retried exactly once.
  for (let attempt = 0; attempt < 2; attempt++) {
    console.log(`[ablation] ${label}${attempt ? ' (timeout retry)' : ''}`);
    const heartbeat = setInterval(() => console.log(`[ablation] ${label} is running`), 30000);
    try {
      const result = await capture(command, commandArgs, { cwd, env, signal: controller.signal, timeout: 300000 });
      if (result.code !== 0) throw new Error(`${label} failed (${result.code}):\n${result.output}`);
      return result;
    } catch (error) {
      if (attempt === 1 || !error.message.includes('命令超时')) throw error;
    } finally { clearInterval(heartbeat); }
  }
}
try {
  await assertPortFree(port);
  const maven = findExecutable('mvn');
  await stage('Compile local embedding service', maven, ['-o', '-B', '-ntp', '-f', join(root, 'services/embedding/pom.xml'),
    '-Dmaven.repo.local=' + join(root, '.m2/repository'), 'compile', 'dependency:build-classpath',
    '-Dmdep.outputFile=' + join(runDir, 'embedding-classpath.txt')]);
  const classpath = (await readFile(join(runDir, 'embedding-classpath.txt'), 'utf8')).trim();
  serverLog = await open(join(runDir, 'embedding.log'), 'w');
  server = spawn(findExecutable('java'), ['-Xmx768m', '-XX:ActiveProcessorCount=2', '-XX:-UsePerfData',
    '-Djava.io.tmpdir=' + runDir, '-Duser.home=' + join(runDir, 'home'), '-Dembedding.port=' + port,
    '-cp', join(root, '.runtime/embedding-build/classes') + delimiter + classpath,
    'cn.edu.whu.zhizhida.embedding.EmbeddingServer'],
  { cwd: root, env, detached: process.platform !== 'win32', windowsHide: true, stdio: ['ignore', serverLog.fd, serverLog.fd] });
  let launchError;
  server.on('error', error => { launchError = error; });
  const deadline = Date.now() + 60000;
  let ready = false;
  while (Date.now() < deadline && !controller.signal.aborted) {
    if (launchError) throw launchError;
    if (server.exitCode !== null || server.signalCode !== null)
      throw new Error('Embedding service exited: ' + await readFile(join(runDir, 'embedding.log'), 'utf8'));
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1000) });
      const health = await response.json();
      ready = response.ok && health.service === 'zhizhida-local-embedding-v1'
        && health.model === 'bge-small-zh-v1.5-q' && health.dimension === 512;
    } catch { /* Readiness only; no remote fallback. */ }
    if (ready) break;
    await new Promise(done => setTimeout(done, 250));
  }
  if (!ready) throw new Error('Local BGE did not become ready within 60 seconds.');
  const rawPath = join(runDir, 'report.json');
  const result = await stage('Run production-ranker ablation', maven, ['-o', '-B', '-ntp',
    '-Dzhizhida.build.directory=' + join(runDir, 'backend'), '-Dtest=KnowledgeRetrievalAblationTest,HybridKnowledgeSearchTest,KnowledgeRetrievalMetricsTest',
    '-Dknowledge.ablation.enabled=true', '-Dknowledge.ablation.embeddingPort=' + port,
    '-Dknowledge.ablation.report=' + rawPath, 'test'], join(root, 'backend'));
  const report = JSON.parse(await readFile(rawPath, 'utf8'));
  const sourcePaths = ['scripts/evaluate-retrieval-ablation.mjs',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeRetrievalAblationTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeRetrievalMetrics.java',
    'backend/src/test/resources/knowledge-retrieval.json',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/HybridKnowledgeSearch.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/domain/KnowledgeText.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/KnowledgeRetrievalSettings.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/ai/LangChain4jKnowledgeVectors.java',
    'services/embedding/src/main/java/cn/edu/whu/zhizhida/embedding/EmbeddingServer.java'];
  report.generatedAt = new Date().toISOString();
  report.command = 'node scripts/evaluate-retrieval-ablation.mjs';
  report.sourceSha256 = Object.fromEntries(await Promise.all(sourcePaths.map(async path => [path, hash(await readFile(join(root, path)))])));
  const modelJar = classpath.split(delimiter).find(path => /langchain4j-embeddings-bge-small-zh-v15-q-[^/\\]+\.jar$/.test(path));
  if (!modelJar) throw new Error('Cannot identify the BGE model artifact in the runtime classpath.');
  report.embeddingArtifact = { file: modelJar.split(/[\\/]/).at(-1), sha256: hash(await readFile(modelJar)) };
  report.validation = result.output.split(/\r?\n/).filter(line => /Tests run:|BUILD SUCCESS/.test(line));
  await writeFile(join(root, 'docs/evidence/retrieval-ablation.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ pairedEffects: report.pairedEffects, corpus: report.corpus, validation: report.validation }, null, 2));
} finally {
  await stopChild(server);
  await serverLog?.close();
  const actual = await realpath(runDir), allowed = await realpath(runtimeRoot);
  if (!actual.startsWith(allowed + sep) || actual !== runDir) throw new Error('Refusing cleanup outside this run directory');
  await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
  process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt);
  console.log('[ablation] Stopped this run\'s model process and removed its temporary directory.');
}
