import assert from 'node:assert/strict';
import test from 'node:test';
import { search } from './catalog.mjs';

test('finds local reading materials by their technical concepts', () => {
  const cases = [
    ['RAG 评测 Evidence Recall 和 MRR', 'https://javaguide.cn/ai/rag/rag-evaluation.html'],
    ['混合检索和 Rerank 重排', 'https://javaguide.cn/ai/rag/rag-optimization.html'],
    ['ＭＣＰ 与 Function Calling 的区别', 'https://javaguide.cn/ai/agent/mcp.html'],
    ['Agent Loop 如何停止', 'https://javaguide.cn/ai/agent/agent-basis.html'],
    ['Cache Aside 缓存一致性', 'https://javaguide.cn/database/redis/3-commonly-used-cache-read-and-write-strategies.html'],
    ['Future.get 超时和 cancel(true)', 'https://javaguide.cn/java/concurrent/java-thread-pool-best-practices.html'],
    ['Spring @Transactional 自调用', 'https://javaguide.cn/system-design/framework/spring/spring-transaction.html'],
    ['延迟消息的交付时间', 'https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/'],
    ['幂等去重表应该何时回滚', 'https://javaguide.cn/high-availability/idempotency.html'],
    ['覆盖索引如何避免回表', 'https://javaguide.cn/database/mysql/mysql-index.html'],
    ['Read View 如何沿版本链查找', 'https://xiaolincoding.com/mysql/transaction/mvcc.html'],
    ['undo log 与 redo log、binlog 的职责', 'https://xiaolincoding.com/mysql/log/how_update.html'],
    ['Redis 惰性删除与定期删除', 'https://xiaolincoding.com/redis/module/strategy.html'],
    ['HTTP 协商缓存 ETag 304', 'https://xiaolincoding.com/network/2_http/http_interview.html'],
    ['TCP 的 RTT 和 RTO', 'https://xiaolincoding.com/network/3_tcp/tcp_feature.html'],
    ['进程状态从阻塞态到就绪态', 'https://xiaolincoding.com/os/4_process/process_base.html'],
    ['进程间通信中的匿名管道与共享内存', 'https://xiaolincoding.com/os/4_process/process_commu.html'],
  ];
  for (const [query, url] of cases) {
    const [reference] = search(query, 1);
    assert.equal(reference.url, url, query);
    assert.deepEqual(Object.keys(reference), ['title', 'url', 'summary']);
  }
});
