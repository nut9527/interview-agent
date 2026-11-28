// 使用本地 BGE 复测检索耗时，按选定资料和全库两种范围诊断候选排名与分块效果。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, open, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { arch, cpus, platform, release, totalmem } from 'node:os';
import { delimiter, join, resolve, sep } from 'node:path';
import { PROJECT_ROOT as root, assertPortFree, capture, findExecutable, stopChild, waitForHttp } from './dev.mjs';
import { loadCorpus, validateCorpus } from './rag-corpus.mjs';

if (process.argv.includes('--help')) {
  console.log('Usage: node scripts/evaluate-reference-retrieval.mjs [--diagnostics | --chunking]\nUses offline Maven, a private BGE process on 18093 and independent build directories. Default: three performance runs. --diagnostics: selected/full-library Top-30 comparison. --chunking: frozen old/current chunks, both routes and scopes with timing. Writes JSON with a random run ID under docs/evidence.');
  process.exit(0);
}
const chunkingMode = process.argv[2] === '--chunking';
const diagnosticMode = process.argv[2] === '--diagnostics';
assert.equal(process.argv.length, diagnosticMode || chunkingMode ? 3 : 2);
const repetitions = diagnosticMode || chunkingMode ? 1 : 3, port = 18093;
const runtimeRoot = join(root, '.runtime');
await mkdir(runtimeRoot, { recursive: true });
const runDir = await mkdtemp(join(runtimeRoot, 'reference-retrieval-'));
const env = { ...process.env, TMP: runDir, TEMP: runDir,
  DJL_CACHE_DIR: join(runDir, 'native'), ENGINE_CACHE_DIR: join(runDir, 'native'),
  MAVEN_OPTS: `-Djava.io.tmpdir=${runDir} -XX:-UsePerfData` };
const abort = new AbortController();
const interrupt = () => abort.abort();
process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const stages = [];
let server, serverLog;

async function stage(label, command, args, cwd = root) {
  for (let attempt = 1; attempt <= 2; attempt++) {
    console.log(`[reference-retrieval] ${label}, attempt ${attempt}`);
    const started = performance.now();
    const heartbeat = setInterval(() => console.log(`[reference-retrieval] ${label} running`), 30000);
    try {
      const result = await capture(command, args, { cwd, env, timeout: 300000, signal: abort.signal });
      assert.equal(result.code, 0, `${label}:\n${result.output}`);
      stages.push({ label, attempt, wallMs: Math.round(performance.now() - started),
        validation: result.output.split(/\r?\n/).filter(line => /Tests run:|BUILD SUCCESS/.test(line)) });
      return;
    } catch (error) {
      if (attempt === 2 || !error.message.includes('命令超时')) throw error;
    } finally { clearInterval(heartbeat); }
  }
}

function latency(rows) {
  const values = rows.map(row => row.micros).sort((a, b) => a - b);
  const percentile = value => values[Math.ceil(values.length * value) - 1];
  return { querySamples: values.length, p50Micros: percentile(0.50), p95Micros: percentile(0.95),
    minMicros: values[0], maxMicros: values.at(-1) };
}

function summary(quality, rows) {
  const nonEmpty = quality.noAnswerCases - quality.noAnswerWithEmptyRetrieval;
  return { ...quality, noAnswerWithNonEmptyRetrieval: nonEmpty,
    nonEmptyRetrievalRateOnNoAnswer: quality.noAnswerCases ? nonEmpty / quality.noAnswerCases : null,
    latency: latency(rows) };
}

try {
  const corpus = await loadCorpus();
  const corpusSummary = validateCorpus(corpus);
  const scopeAdjustment = JSON.parse(await readFile(join(root, 'backend/src/test/resources/rag-corpus/scope-adjustments.json'), 'utf8'));
  const maven = findExecutable('mvn');
  const backendBuild = join(runDir, 'backend');
  const backendClasspathFile = join(runDir, 'backend-classpath.txt');
  await stage('Compile isolated backend tests', maven, ['-o', '-B', '-ntp',
    '-Dzhizhida.build.directory=' + backendBuild, '-DskipTests', '-Djacoco.skip=true',
    'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=' + backendClasspathFile], join(root, 'backend'));
  const embeddingClasspathFile = join(runDir, 'embedding-classpath.txt');
  await stage('Resolve cached embedding dependencies', maven, ['-o', '-B', '-ntp',
    '-f', join(root, 'services/embedding/pom.xml'), '-Dmaven.repo.local=' + join(root, '.m2/repository'),
    'dependency:build-classpath', '-Dmdep.outputFile=' + embeddingClasspathFile]);
  const embeddingClasspath = (await readFile(embeddingClasspathFile, 'utf8')).trim();
  const embeddingClasses = join(runDir, 'embedding-classes');
  await mkdir(embeddingClasses);
  await stage('Compile isolated embedding service', findExecutable('javac'), ['-encoding', 'UTF-8',
    '-cp', embeddingClasspath, '-d', embeddingClasses,
    join(root, 'services/embedding/src/main/java/cn/edu/whu/zhizhida/embedding/EmbeddingServer.java')]);

  const statsSource = join(runDir, 'ReferenceCorpusStats.java');
  const statsPath = join(runDir, 'corpus-stats.json');
  await writeFile(statsSource, `package cn.edu.whu.zhizhida.knowledge;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
public class ReferenceCorpusStats {
 public static void main(String[] args) throws Exception {
  var json = new JsonMapper(); var fixture = KnowledgeCorpusFixture.load(json, KnowledgeCorpusFixture.RESOURCE);
  var documents = new ArrayList<Map<String,Object>>(); int total = 0, evidence = 0;
  for (var document : fixture.path("documents")) {
   var chunks = KnowledgeText.split(document.path("id").asString(), 1, document.path("content").asString());
   int eligible = (int) chunks.stream().filter(KnowledgeText::isEvidence).count(); total += chunks.size(); evidence += eligible;
   documents.add(Map.of("id", document.path("id").asString(), "chunks", chunks.size(), "evidenceChunks", eligible,
    "split", document.path("split").asString(), "contentSha256", document.path("contentSha256").asString()));
  }
  Files.writeString(Path.of(args[0]), json.writeValueAsString(Map.of("documents", documents, "chunks", total,
   "evidenceChunks", evidence, "fixtureSha256", fixture.path("fixtureSha256").asString())));
 }
}
`);
  const backendClasspath = [join(backendBuild, 'test-classes'), join(backendBuild, 'classes'),
    (await readFile(backendClasspathFile, 'utf8')).trim()].join(delimiter);
  await stage('Compile corpus statistics helper', findExecutable('javac'), ['-encoding', 'UTF-8',
    '-cp', backendClasspath, '-d', runDir, statsSource]);
  await stage('Count production chunks', findExecutable('java'), ['-XX:-UsePerfData',
    '-Djava.io.tmpdir=' + runDir, '-cp', runDir + delimiter + backendClasspath,
    'cn.edu.whu.zhizhida.knowledge.ReferenceCorpusStats', statsPath]);
  const chunkStats = JSON.parse(await readFile(statsPath, 'utf8'));

  await assertPortFree(port);
  serverLog = await open(join(runDir, 'embedding.log'), 'w');
  server = spawn(findExecutable('java'), ['-Xmx768m', '-XX:ActiveProcessorCount=2', '-XX:-UsePerfData',
    '-Djava.io.tmpdir=' + runDir, '-Duser.home=' + join(runDir, 'home'), '-Dembedding.port=' + port,
    '-cp', embeddingClasses + delimiter + embeddingClasspath, 'cn.edu.whu.zhizhida.embedding.EmbeddingServer'],
    { cwd: root, env, windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', serverLog.fd, serverLog.fd] });
  await waitForHttp(`http://127.0.0.1:${port}/health`, async response => {
    const health = await response.json();
    return health.model === 'bge-small-zh-v1.5-q' && health.dimension === 512;
  }, { timeout: 60000, alive: () => server.exitCode === null });
  console.log('[reference-retrieval] Private BGE ready; starting measured rounds.');

  const runs = [], modes = {};
  let diagnostics, chunking;
  if (chunkingMode) {
    const unitTests = '*Knowledge*Test,HybridKnowledgeSearchTest,QuizSourceSelectorTest,LangChain4jVectorsTest';
    await stage('Verify chunking and retrieval regressions', maven, ['-o', '-B', '-ntp',
      '-Dzhizhida.build.directory=' + backendBuild, '-Djacoco.skip=true', '-Dtest=' + unitTests,
      '-Dknowledge.eval.report=' + join(runDir, 'unit-retrieval.json'),
      '-Dknowledge.eval.hybridReport=' + join(runDir, 'unit-hybrid.json'), 'surefire:test'], join(root, 'backend'));
    const baseline = 'backend/src/test/resources/rag-corpus/baseline/KnowledgeText.java';
    chunking = {};
    for (const [variant, source] of [['old', baseline], ['new', 'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/domain/KnowledgeText.java']]) {
      await stage('Compile ' + variant + ' chunking', findExecutable('javac'), ['-encoding', 'UTF-8', '-cp', backendClasspath,
        '-d', join(backendBuild, 'classes'), join(root, source)]);
      const reportPath = join(runDir, variant + '-chunking.json');
      await stage('Evaluate ' + variant + ' chunking', maven, ['-o', '-B', '-ntp',
        '-Dzhizhida.build.directory=' + backendBuild, '-Djacoco.skip=true', '-DargLine=-XX:ActiveProcessorCount=2 -XX:-UsePerfData',
        '-Dtest=ReferenceRetrievalDiagnosticsTest', '-Dknowledge.diagnostics.enabled=true', '-Dknowledge.diagnostics.measure=true',
        '-Dknowledge.diagnostics.embeddingPort=' + port, '-Dknowledge.diagnostics.report=' + reportPath, 'surefire:test'], join(root, 'backend'));
      chunking[variant] = JSON.parse(await readFile(reportPath, 'utf8'));
    }
  } else if (diagnosticMode) {
    const reportPath = join(runDir, 'diagnostics.json');
    await stage('Diagnose selected and full-library candidates', maven, ['-o', '-B', '-ntp',
      '-Dzhizhida.build.directory=' + backendBuild, '-Djacoco.skip=true',
      '-DargLine=-XX:ActiveProcessorCount=2 -XX:-UsePerfData',
      '-Dtest=ReferenceRetrievalDiagnosticsTest,HybridKnowledgeSearchTest,LangChain4jVectorsTest,KnowledgeCorpusFixtureTest,KnowledgeRetrievalMetricsTest',
      '-Dknowledge.diagnostics.enabled=true', '-Dknowledge.diagnostics.embeddingPort=' + port,
      '-Dknowledge.diagnostics.report=' + reportPath, 'surefire:test'], join(root, 'backend'));
    diagnostics = JSON.parse(await readFile(reportPath, 'utf8'));
  } else {
    for (let round = 1; round <= repetitions; round++) {
      const reportPath = join(runDir, `round-${round}.json`);
      await stage(`Retrieval round ${round}`, maven, ['-o', '-B', '-ntp',
        '-Dzhizhida.build.directory=' + backendBuild, '-Djacoco.skip=true',
        '-DargLine=-XX:ActiveProcessorCount=2 -XX:-UsePerfData',
        '-Dtest=KnowledgeHybridRetrievalEvaluationTest,KnowledgeCorpusFixtureTest,KnowledgeRetrievalMetricsTest',
        '-Dknowledge.eval.fixture=/rag-corpus/manifest.json', '-Dknowledge.eval.hybridReport=' + reportPath,
        '-Dknowledge.eval.embeddingModel=bge-small-zh-v1.5-q', '-Dknowledge.eval.embeddingDimension=512',
        '-Dknowledge.eval.embeddingPort=' + port, '-Dagent.retrieval.rrf-k=60',
        '-Dagent.retrieval.lexical-top-n=30', '-Dagent.retrieval.vector-top-n=30',
        '-Dagent.retrieval.max-per-heading=2', 'surefire:test'], join(root, 'backend'));
      runs.push(JSON.parse(await readFile(reportPath, 'utf8')));
    }

    for (const mode of ['lexical', 'hybrid']) {
      const first = runs[0][mode], allRows = runs.flatMap(run => run[mode].details);
      for (const run of runs) {
        assert.deepEqual(run.fixture, runs[0].fixture);
        assert.deepEqual(run[mode].details.map(({ micros, ...row }) => row),
          first.details.map(({ micros, ...row }) => row), 'Rankings changed between repetitions');
      }
      modes[mode] = { fingerprint: first.mode,
        all: summary(first.metricsByKind.all, allRows),
        bySplit: Object.fromEntries(Object.entries(first.metricsBySplit).map(([split, value]) =>
          [split, summary(value.all, allRows.filter(row => row.split === split))])),
        byKind: Object.fromEntries(Object.entries(first.metricsByKind).filter(([kind]) => kind !== 'all').map(([kind, value]) =>
          [kind, summary(value, allRows.filter(row => row.kind === kind))])),
        details: first.details.map(({ micros, ...row }) => ({ ...row,
          scope: corpus.cases.find(item => item.id === row.id).scope,
          latencySamplesMicros: runs.map(run => run[mode].details.find(item => item.id === row.id).micros) })) };
    }
  }
  const sourcePaths = ['scripts/evaluate-reference-retrieval.mjs', 'scripts/rag-corpus.mjs',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeHybridRetrievalEvaluationTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeCorpusFixture.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeRetrievalMetrics.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/HybridKnowledgeSearch.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/domain/KnowledgeText.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/KnowledgeRetrievalSettings.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/ai/LangChain4jKnowledgeVectors.java',
    'services/embedding/src/main/java/cn/edu/whu/zhizhida/embedding/EmbeddingServer.java'];
  if (diagnosticMode || chunkingMode) sourcePaths.push(
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/ReferenceRetrievalDiagnosticsTest.java',
    'backend/src/test/resources/rag-corpus/full-library-answerability.json');
  if (chunkingMode) sourcePaths.push('backend/src/test/resources/rag-corpus/baseline/KnowledgeText.java',
    'backend/src/main/java/cn/edu/whu/zhizhida/knowledge/TrainingKnowledgeCatalog.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeTextTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/TrainingKnowledgeCatalogTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/HybridKnowledgeSearchTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeIndexWorkerTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeLearningServiceTest.java',
    'backend/src/test/java/cn/edu/whu/zhizhida/knowledge/QuizSourceSelectorTest.java');
  const modelJar = embeddingClasspath.split(delimiter).find(path => /langchain4j-embeddings-bge-small-zh-v15-q-[^/\\]+\.jar$/.test(path));
  const report = { schemaVersion: 1, runId: randomUUID(),
    command: 'node scripts/evaluate-reference-retrieval.mjs' + (chunkingMode ? ' --chunking' : diagnosticMode ? ' --diagnostics' : ''), repetitions,
    corpus: { ...corpusSummary, ...chunkStats, documentCount: corpusSummary.documents }, scopeAdjustment,
    measurement: chunkingMode ? {
      purpose: 'Paired old/new chunking, lexical/hybrid, selected/all; same questions and original gold quotes.',
      qualityCases: corpus.cases.length, timingSamplesPerVariantScopeMode: corpus.cases.length * 3,
      timing: 'Three warm measurements per question. Hybrid includes uncached loopback HTTP embedding, vector search, RRF and quota. Excludes indexing and scope filtering.',
      baseline: 'Frozen pre-change KnowledgeText compiled in the same isolated build; all other code and BGE settings identical.',
    } : diagnosticMode ? {
      qualityCases: corpus.cases.length,
      scope: 'Same questions and labels; selected documents versus all reference documents.',
      purpose: 'Candidate and ranking diagnostics, not a latency benchmark or deployed full-library API.',
      labels: 'Annotated sections and exact evidence quotes are scored separately; unjudged alternative evidence is retained for review.',
    } : { qualityCases: corpus.cases.length, latencySamplesPerMode: corpus.cases.length * repetitions,
      warmup: 'First query of each mode in each JVM is executed once without recording time.',
      scope: 'Per-question selected documents, at most five. Not unrestricted search across the complete corpus.',
      timing: 'Starts after per-question scope selection. Includes lexical ranking; hybrid also includes loopback HTTP embedding, in-memory vector search, RRF and section diversification. Excludes scope filtering, indexing, SQL and answer generation.',
      noAnswer: 'Nonempty candidate retrieval on labeled no-answer cases; not an answer-generation hallucination rate.',
      baseline: 'Both modes rerun after scope correction; previous broader-scope lexical scores are not this baseline.' },
    configuration: { model: 'bge-small-zh-v1.5-q', dimension: 512, vectorStore: 'InMemoryEmbeddingStore',
      minimumVectorScore: 0.55, rrfK: 60, lexicalTopN: 30, vectorTopN: 30, maxPerHeading: 2, limit: 6,
      embeddingActiveProcessorCount: 2, evaluationActiveProcessorCount: 2 },
    environment: { platform: platform(), release: release(), architecture: arch(),
      cpu: cpus()[0].model, logicalProcessors: cpus().length, memoryBytes: totalmem() },
    modelArtifact: { file: modelJar.split(/[\\/]/).at(-1), sha256: sha256(await readFile(modelJar)) },
    sourceSha256: Object.fromEntries(await Promise.all(sourcePaths.map(async path => [path, sha256(await readFile(join(root, path)))]))),
    ...(chunkingMode ? { chunking } : diagnosticMode ? { diagnostics } : { modes }), stages };
  const name = chunkingMode ? 'reference-chunking' : diagnosticMode ? 'reference-retrieval-diagnostics' : 'reference-retrieval-bge';
  const output = join(root, `docs/evidence/${name}-${report.runId}.json`);
  await writeFile(output, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, corpus: corpusSummary, chunks: chunkStats.chunks,
    ...(chunkingMode ? { variants: Object.keys(chunking) } : diagnosticMode ? { diagnosticCases: diagnostics.cases, scopes: Object.keys(diagnostics.summary) }
      : { lexical: modes.lexical.all, hybrid: modes.hybrid.all }) }, null, 2));
} finally {
  await stopChild(server);
  await serverLog?.close();
  const actual = await realpath(runDir), allowed = await realpath(runtimeRoot);
  assert(actual.startsWith(allowed + sep) && actual === resolve(runDir));
  await rm(actual, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
  process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt);
  console.log('[reference-retrieval] Stopped owned model process and removed this run directory.');
}
