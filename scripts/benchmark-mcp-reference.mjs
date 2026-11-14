import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { availableParallelism, cpus, platform, release, totalmem } from 'node:os';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { referenceServer } from '../services/mcp-reference/server.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const workload = [
  ['redis-expire', 'Redis', 'EXPIRE 设置 TTL 后覆盖键值会怎样', 'https://redis.io/docs/latest/commands/expire/'],
  ['redis-eviction', 'Redis', 'maxmemory 配合 eviction LRU 和 LFU', 'https://redis.io/docs/latest/develop/reference/eviction/'],
  ['rabbit-confirms', 'RabbitMQ', 'publisher confirm 与消费确认和重投', 'https://www.rabbitmq.com/docs/confirms'],
  ['mysql-isolation', 'MySQL', 'InnoDB 事务隔离和快照读', 'https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html'],
  ['mysql-explain', 'MySQL', '慢查询 EXPLAIN 执行计划', 'https://dev.mysql.com/doc/refman/8.4/en/explain.html'],
  ['java-map', 'Java', 'HashMap 可变键 equals hashCode 契约', 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Map.html'],
  ['java-executor', 'Java', 'ThreadPoolExecutor 无界队列的拒绝策略', 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html'],
  ['java-jfr', 'Java', '用 JFR 排查 CPU 与线程等待', 'https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html'],
  ['sre-monitoring', '可观测性', '监控延迟 p99 分位数指标', 'https://sre.google/workbook/monitoring/'],
  ['rag-basis', 'RAG', '检索增强生成中 Embedding 与文档切分', 'https://javaguide.cn/ai/rag/rag-basis.html'],
  ['rag-optimization', 'RAG', '混合检索 Hybrid Search 和 Rerank 重排', 'https://javaguide.cn/ai/rag/rag-optimization.html'],
  ['rag-evaluation', 'RAG', 'RAG 评测中的 Evidence Recall MRR 与可回答性', 'https://javaguide.cn/ai/rag/rag-evaluation.html'],
  ['agent-loop', 'Agent/MCP', 'Agent Loop 和 Plan-and-Execute 如何结束', 'https://javaguide.cn/ai/agent/agent-basis.html'],
  ['mcp-calling', 'Agent/MCP', 'ＭＣＰ 与 Function Calling 的工具发现', 'https://javaguide.cn/ai/agent/mcp.html'],
  ['cache-levels', '缓存', '本地缓存 分布式缓存 多级缓存 L1 L2', 'https://javaguide.cn/database/redis/cache-basics.html'],
  ['cache-aside', '缓存', 'Cache Aside 旁路缓存怎样保持缓存一致性', 'https://javaguide.cn/database/redis/3-commonly-used-cache-read-and-write-strategies.html'],
  ['thread-pool', 'Java', '线程池原理 corePoolSize maximumPoolSize 阻塞队列', 'https://javaguide.cn/java/concurrent/java-thread-pool-summary.html'],
  ['thread-practices', 'Java', 'Future.get 超时和 cancel(true) 中断响应', 'https://javaguide.cn/java/concurrent/java-thread-pool-best-practices.html'],
  ['transaction-levels', 'MySQL', 'READ COMMITTED 为什么会不可重复读', 'https://javaguide.cn/database/mysql/transaction-isolation-level.html'],
  ['mysql-index', 'MySQL', '覆盖索引和二级索引如何避免回表', 'https://javaguide.cn/database/mysql/mysql-index.html'],
  ['mysql-plan-fields', 'MySQL', 'possible_keys key_len 与 Using filesort 表示什么', 'https://javaguide.cn/database/mysql/mysql-query-execution-plan.html'],
  ['spring-transaction', 'Spring', '@Transactional 自调用和 rollbackFor', 'https://javaguide.cn/system-design/framework/spring/spring-transaction.html'],
  ['rabbit-dlx', 'RabbitMQ', 'RabbitMQ 死信交换机 DLX 与 routing key', 'https://javaguide.cn/high-performance/message-queue/rabbitmq-questions.html'],
  ['idempotency', '可靠性', '幂等去重表和业务唯一键怎样保证重复请求安全', 'https://javaguide.cn/high-availability/idempotency.html'],
  ['mysql-mvcc', 'MySQL', 'Read View 如何沿版本链判断事务可见性', 'https://xiaolincoding.com/mysql/transaction/mvcc.html'],
  ['mysql-logs', 'MySQL', 'undo log redo log 和 binlog 的职责', 'https://xiaolincoding.com/mysql/log/how_update.html'],
  ['redis-expiration', 'Redis', '惰性删除和定期删除有什么区别', 'https://xiaolincoding.com/redis/module/strategy.html'],
  ['http-cache', '网络', 'HTTP 协商缓存 ETag 返回 304', 'https://xiaolincoding.com/network/2_http/http_interview.html'],
  ['tcp-retry', '网络', 'TCP 重传中 RTT 与 RTO 的关系', 'https://xiaolincoding.com/network/3_tcp/tcp_feature.html'],
  ['process-state', '操作系统', '进程状态怎样从阻塞态转为就绪态', 'https://xiaolincoding.com/os/4_process/process_base.html'],
  ['process-ipc', '操作系统', '进程间通信中的匿名管道与共享内存', 'https://xiaolincoding.com/os/4_process/process_commu.html'],
].map(([id, topic, query, expectedUrl]) => ({ id, topic, query, expectedUrl }));
const negativeQueries = ['如何烘焙戚风蛋糕', '推荐一部科幻电影', '太阳系行星的轨道周期'];
const sourcePaths = ['services/mcp-reference/server.mjs', 'services/mcp-reference/catalog.mjs', 'scripts/benchmark-mcp-reference.mjs'];
const sourceHashes = Object.fromEntries(await Promise.all(sourcePaths.map(async path => [path, createHash('sha256').update(await readFile(join(root, path))).digest('hex')])));
const catalogSource = await readFile(join(root, 'services/mcp-reference/catalog.mjs'), 'utf8');
const catalogUrls = [...catalogSource.matchAll(/^\s+url: '([^']+)'/gm)].map(match => match[1]);
assert.equal(workload.length, 31);
assert.deepEqual([...new Set(workload.map(item => item.expectedUrl))].sort(), catalogUrls.sort());

if (process.argv.includes('--server')) {
  const server = referenceServer().listen(0, '127.0.0.1');
  await once(server, 'listening');
  process.send({ port: server.address().port });
  process.once('message', () => server.close(() => process.exit()));
  process.once('disconnect', () => server.close(() => process.exit()));
} else if (process.argv.includes('--prepare')) {
  console.log(JSON.stringify({ prepared: true, catalogEntries: catalogUrls.length, queries: workload.length, negativeQueries: negativeQueries.length, sourceHashes }, null, 2));
} else {
  assert.equal(process.argv.length, 2, 'Usage: node scripts/benchmark-mcp-reference.mjs [--prepare]');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('MCP benchmark exceeded 240 seconds')), 240000);
  const child = fork(fileURLToPath(import.meta.url), ['--server'], { cwd: root, windowsHide: true, stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
  let childError = '';
  child.stderr.on('data', bytes => { childError += bytes.toString(); });
  let requestId = 0;
  const runId = randomUUID();
  const rounds = [];
  const relevance = [];
  const negatives = [];
  const requestsPerRound = workload.length * 100;
  const report = { schemaVersion: 'mcp-http-benchmark-v1', runId, status: 'RUNNING', sourceHashes,
    environment: { node: process.version, platform: platform(), release: release(), architecture: process.arch,
      cpu: cpus()[0].model, logicalProcessors: cpus().length, availableParallelism: availableParallelism(), memoryBytes: totalmem() },
    method: { serverProcesses: 1, clientProcesses: 1, transport: 'HTTP/1.1 JSON-RPC POST on random loopback port', protocolVersion: '2025-06-18',
      catalogEntries: catalogUrls.length, topK: 3, requestsPerRound, roundsPerConcurrency: 3, concurrency: [1, 8],
      order: [1, 8, 8, 1, 1, 8], warmupPerRound: workload.length * 10, workloadOrder: 'round-robin from index 0 in every round',
      latency: 'client monotonic time from request serialization through HTTP body and nested JSON parse; excludes result assertion',
      percentiles: 'nearest rank', loadModel: 'closed loop; next request starts after prior response on each client slot',
      realProviderCalls: 0 }, workload, negativeQueries, relevance, negatives, rounds,
    scope: '本机预热后的真实 HTTP 闭环测试，31 条定向查询覆盖关键词目录；耗时包含客户端与 JSON 处理，无外部模型调用。' };
  const stats = values => {
    const sorted = [...values].sort((a, b) => a - b);
    const percentile = p => sorted[Math.ceil(p * sorted.length) - 1];
    return { count: values.length, meanMs: values.reduce((a, b) => a + b, 0) / values.length,
      p50Ms: percentile(.5), p95Ms: percentile(.95), p99Ms: percentile(.99), minMs: sorted[0], maxMs: sorted.at(-1) };
  };
  try {
    const [ready] = await once(child, 'message', { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]) });
    const url = 'http://127.0.0.1:' + ready.port + '/mcp';
    const rpc = async (method, params) => {
      const before = performance.now();
      const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', 'mcp-protocol-version': '2025-06-18' },
        body: JSON.stringify({ jsonrpc: '2.0', id: ++requestId, method, params }), signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) });
      const body = await response.json();
      const references = body.result?.content ? JSON.parse(body.result.content[0].text) : undefined;
      const elapsedMs = performance.now() - before;
      assert.equal(response.status, 200);
      assert.equal(body.error, undefined);
      return { body, references, elapsedMs };
    };
    const search = query => rpc('tools/call', { name: 'reference_search', arguments: { query, topK: 3 } });
    assert.equal((await rpc('initialize', {})).body.result.protocolVersion, '2025-06-18');
    assert.deepEqual((await rpc('tools/list', {})).body.result.tools.map(tool => tool.name), ['reference_search']);
    for (const item of workload) {
      const { references } = await search(item.query);
      relevance.push({ id: item.id, expectedUrl: item.expectedUrl, actualUrls: references.map(reference => reference.url),
        top1: references[0]?.url === item.expectedUrl, top3: references.some(reference => reference.url === item.expectedUrl) });
    }
    for (const query of negativeQueries) {
      const { references } = await search(query);
      negatives.push({ query, actualUrls: references.map(reference => reference.url), empty: references.length === 0 });
    }
    report.topicHits = [...new Set(workload.map(item => item.topic))].map(topic => {
      const ids = workload.filter(item => item.topic === topic).map(item => item.id);
      const results = relevance.filter(item => ids.includes(item.id));
      return { topic, queries: results.length, top1: results.filter(item => item.top1).length, top3: results.filter(item => item.top3).length };
    });
    const batch = async (concurrency, count) => {
      let next = 0, top1Matches = 0;
      const samplesMs = new Array(count), failures = [];
      const before = performance.now();
      await Promise.all(Array.from({ length: concurrency }, async () => {
        for (;;) {
          const index = next++;
          if (index >= count) return;
          const item = workload[index % workload.length];
          try {
            const { references, elapsedMs } = await search(item.query);
            samplesMs[index] = elapsedMs;
            if (references[0]?.url === item.expectedUrl) top1Matches++;
          } catch (error) { failures.push({ index, queryId: item.id, message: error.message }); }
        }
      }));
      const elapsedSeconds = (performance.now() - before) / 1000;
      return { concurrency, requests: count, successes: count - failures.length, top1Matches, failures, elapsedSeconds,
        throughputPerSecond: (count - failures.length) / elapsedSeconds, latency: stats(samplesMs.filter(Number.isFinite)), samplesMs };
    };
    for (const concurrency of report.method.order) {
      const warmup = await batch(concurrency, report.method.warmupPerRound);
      assert.equal(warmup.failures.length, 0, 'Warmup must succeed before recording a round');
      const result = await batch(concurrency, requestsPerRound);
      rounds.push({ round: rounds.length + 1, ...result });
      console.log(JSON.stringify({ round: rounds.length, concurrency, requests: result.requests, failures: result.failures.length,
        throughputPerSecond: result.throughputPerSecond, p95Ms: result.latency.p95Ms, p99Ms: result.latency.p99Ms }));
    }
    report.summary = [1, 8].map(concurrency => {
      const selected = rounds.filter(round => round.concurrency === concurrency);
      const successes = selected.reduce((sum, round) => sum + round.successes, 0);
      return { concurrency, successes, failures: selected.reduce((sum, round) => sum + round.failures.length, 0),
        top1Matches: selected.reduce((sum, round) => sum + round.top1Matches, 0),
        throughputPerSecond: successes / selected.reduce((sum, round) => sum + round.elapsedSeconds, 0),
        latency: stats(selected.flatMap(round => round.samplesMs).filter(Number.isFinite)) };
    });
    report.protocolStatus = rounds.every(row => !row.failures.length) ? 'PASSED' : 'FAILED';
    report.retrievalStatus = relevance.every(row => row.top1) && negatives.every(row => row.empty) ? 'PASSED' : 'MISMATCH';
    report.status = report.protocolStatus === 'PASSED' && report.retrievalStatus === 'PASSED' ? 'PASSED' : 'FAILED';
  } catch (error) { report.status = 'FAILED'; report.error = error.message; }
  finally {
    clearTimeout(timer);
    if (child.connected) child.send('stop');
    try { if (child.exitCode === null) await once(child, 'exit', { signal: AbortSignal.timeout(5000) }); }
    catch { child.kill(); await once(child, 'exit'); }
    if (childError) report.serverStderr = childError;
  }
  const evidenceDirectory = join(root, 'docs/evidence');
  await mkdir(evidenceDirectory, { recursive: true });
  const name = 'mcp-http-' + runId + '.json';
  await writeFile(join(evidenceDirectory, name), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ status: report.status, evidence: relative(root, join(evidenceDirectory, name)), summary: report.summary,
    top1: relevance.filter(row => row.top1).length + '/' + workload.length, emptyNegatives: negatives.filter(row => row.empty).length + '/' + negativeQueries.length }, null, 2));
  if (report.status !== 'PASSED') process.exitCode = 1;
}
