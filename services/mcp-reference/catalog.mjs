// Locally curated reading notes. Only the reader's browser follows a source link.
const catalog = [
  {
    title: 'Redis · EXPIRE 与键的生存时间',
    url: 'https://redis.io/docs/latest/commands/expire/',
    summary: '对照 EXPIRE 与 TTL 的示例，验证覆盖键值、修改值内容以及重新设置过期时间时，剩余生存时间分别怎样变化。',
    terms: ['过期', 'ttl', '生存时间', 'expire', '缓存失效'],
  },
  {
    title: 'Redis · 内存上限和淘汰策略',
    url: 'https://redis.io/docs/latest/develop/reference/eviction/',
    summary: '比较 allkeys 和 volatile 策略的候选键范围。达到 maxmemory 时，观察淘汰计数、命中率及写入失败情况。',
    terms: ['淘汰', 'eviction', 'maxmemory', '命中率', '容量保护', 'lru', 'lfu'],
  },
  {
    title: 'RabbitMQ · 消费确认与发布确认',
    url: 'https://www.rabbitmq.com/docs/confirms',
    summary: '沿着一条消息分别找出 broker 向生产者确认、消费者向 broker 确认的时刻，再分析消费者提交数据库后断线会触发什么行为。',
    terms: ['rabbitmq', '消息', '重投', '重复消费', '消费确认', 'acknowledgement', 'publisher confirm'],
  },
  {
    title: 'MySQL · InnoDB 事务隔离级别',
    url: 'https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html',
    summary: '按隔离级别比较普通 SELECT 和锁定读。设计两个会话交错读写同一行的实验，记录每一步能看到哪个版本。',
    terms: ['隔离', '事务', 'innodb', '快照读', 'mvcc', '幻读', '锁定读'],
  },
  {
    title: 'MySQL · EXPLAIN 与执行计划',
    url: 'https://dev.mysql.com/doc/refman/8.4/en/explain.html',
    summary: '从 EXPLAIN 看访问路径、索引选择及估算值；在可控环境用 EXPLAIN ANALYZE 对照实际执行的行数和耗时。',
    terms: ['索引', 'explain', '执行计划', '慢查询'],
  },
  {
    title: 'Java 21 · Map 的键契约',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Map.html',
    summary: '查看 Map 对可变键的约束。用两个相等的键做一次查询，再改变参与 equals 的字段，分析这一步破坏了哪个前提。',
    terms: ['hashmap', 'equals', 'hashcode', '可变键'],
  },
  {
    title: 'Java 21 · ThreadPoolExecutor',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html',
    summary: '结合队列长度、核心线程数和最大线程数推演新任务如何被分配。把队列填满后，比较不同拒绝策略对调用方的影响。',
    terms: ['线程池', '拒绝策略', 'threadpoolexecutor', '无界队列'],
  },
  {
    title: 'Java 21 · 用 jfr 阅读飞行记录',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html',
    summary: '从 jfr summary 了解录制了哪些事件，再用 print 或 view 关注问题时间窗里的事件，验证关于 CPU、分配或线程等待的假设。',
    terms: ['jfr', 'cpu', '线程等待', '分配', '堆', '内存泄漏'],
  },
  {
    title: 'Google SRE Workbook · 监控',
    url: 'https://sre.google/workbook/monitoring/',
    summary: '对照案例选择能反映用户体验的指标。比较平均响应时间和延迟分布，解释少量慢请求怎样影响诊断。',
    terms: ['监控', '延迟', 'p99', '分位数', '指标'],
  },
];

export function search(query, topK) {
  const normalized = query.normalize('NFKC').toLowerCase();
  return catalog.map(({ terms, ...reference }) => ({
    reference,
    score: terms.filter(term => normalized.includes(term)).length,
  })).filter(hit => hit.score > 0)
    .sort((left, right) => right.score - left.score)
    .slice(0, topK)
    .map(hit => hit.reference);
}
