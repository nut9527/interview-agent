// Source summaries. Only the reader's browser follows a source link.
const catalog = [
  {
    title: 'Redis · EXPIRE 与键的生存时间',
    url: 'https://redis.io/docs/latest/commands/expire/',
    summary: 'EXPIRE 按秒设置键的过期时间；普通 SET 覆盖值会清除原有过期时间，INCR、LPUSH、HSET 等修改内容的操作保留过期时间。',
    terms: ['过期', 'ttl', '生存时间', 'expire', '缓存失效'],
  },
  {
    title: 'Redis · 内存上限和淘汰策略',
    url: 'https://redis.io/docs/latest/develop/reference/eviction/',
    summary: 'maxmemory 限制 Redis 用于数据的内存。allkeys 策略从所有键中选择淘汰对象，volatile 策略只选择设置了过期时间的键；noeviction 拒绝需要新增内存的写入。',
    terms: ['淘汰', 'eviction', 'maxmemory', '命中率', '容量保护', 'lru', 'lfu'],
  },
  {
    title: 'RabbitMQ · 消费确认与发布确认',
    url: 'https://www.rabbitmq.com/docs/confirms',
    summary: '发布确认告知生产者消息已被 broker 接收；消费确认告知 broker 消息已处理。手动确认模式下，连接或通道关闭时尚未确认的消息会重新入队。',
    terms: ['rabbitmq', '消息', '重投', '重复消费', '消费确认', 'acknowledgement', 'publisher confirm'],
  },
  {
    title: 'MySQL · InnoDB 事务隔离级别',
    url: 'https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html',
    summary: 'InnoDB 支持四种事务隔离级别，默认使用 REPEATABLE READ。可重复读事务内的一致性读使用同一快照，READ COMMITTED 的每次一致性读使用新的快照。',
    terms: ['隔离', '事务', 'innodb', '快照读', 'mvcc', '幻读', '锁定读'],
  },
  {
    title: 'MySQL · EXPLAIN 与执行计划',
    url: 'https://dev.mysql.com/doc/refman/8.4/en/explain.html',
    summary: 'EXPLAIN 展示优化器的执行计划，包括表连接顺序、访问路径和索引选择。EXPLAIN ANALYZE 会实际执行语句，并报告各迭代器的耗时、行数与循环次数。',
    terms: ['索引', 'explain', '执行计划', '慢查询'],
  },
  {
    title: 'Java 21 · Map 的键契约',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Map.html',
    summary: 'Map 以键的相等关系查找映射。对象作为键期间，如果修改了影响 equals 比较的状态，Map 的行为不再有契约保证。',
    terms: ['hashmap', 'equals', 'hashcode', '可变键'],
  },
  {
    title: 'Java 21 · ThreadPoolExecutor',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html',
    summary: '线程数少于 corePoolSize 时优先创建线程，否则优先入队；入队失败后才尝试扩容到 maximumPoolSize。线程池关闭或线程与队列均饱和时触发拒绝策略。',
    terms: ['线程池', '拒绝策略', 'threadpoolexecutor', '无界队列'],
  },
  {
    title: 'Java 21 · 用 jfr 阅读飞行记录',
    url: 'https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html',
    summary: 'jfr 命令读取和处理 Flight Recorder 文件。summary 显示录制概况与事件统计，print 输出事件，view 将事件按预定义视图聚合展示。',
    terms: ['jfr', 'cpu', '线程等待', '分配', '堆', '内存泄漏'],
  },
  {
    title: 'Google SRE Workbook · 监控',
    url: 'https://sre.google/workbook/monitoring/',
    summary: '监控系统通过指标、日志和告警呈现系统行为。延迟的第 50、95、99 百分位能显示不同比例请求的速度，平均值可能掩盖少量慢请求。',
    terms: ['监控', '延迟', 'p99', '分位数', '指标'],
  },
  {
    title: 'JavaGuide · RAG 基础概念',
    url: 'https://javaguide.cn/ai/rag/rag-basis.html',
    summary: 'RAG 先检索外部知识，再把证据交给模型生成回答。Embedding 将文本映射为向量，文档切分、检索质量和上下文组织共同影响最终答案。',
    terms: ['rag', '检索增强生成', 'embedding', '语义向量', '文档切分'],
  },
  {
    title: 'JavaGuide · RAG 召回、重排与上下文优化',
    url: 'https://javaguide.cn/ai/rag/rag-optimization.html',
    summary: 'RAG 优化覆盖解析、切分、召回、重排和上下文组织。混合检索兼顾关键词与语义，重排提升证据顺序；单纯增加 Top-K 也会增加噪声、延迟和上下文成本。',
    terms: ['rag优化', 'rag 优化', '混合检索', 'hybrid search', 'rerank', '重排', 'query rewrite', '上下文压缩'],
  },
  {
    title: 'JavaGuide · RAG 评测与证据标注',
    url: 'https://javaguide.cn/ai/rag/rag-evaluation.html',
    summary: 'RAG 评测分别检查检索证据和生成答案。评测集记录问题、原文证据与可回答性，结合 Hit@K、Evidence Recall、MRR 等指标判断检索问题，并按文档族划分开发集与留出集。',
    terms: ['rag评测', 'rag 评测', 'hit@k', 'evidence recall', 'mrr', '可回答性', '评测集', '证据标注'],
  },
  {
    title: 'JavaGuide · Agent 基础与执行循环',
    url: 'https://javaguide.cn/ai/agent/agent-basis.html',
    summary: 'Agent 围绕目标反复选择动作、调用工具并读取执行结果。Agent Loop 将模型决策与实际执行连接起来，完成条件、步骤预算和工具结果决定循环何时结束。',
    terms: ['agent', '智能体', 'agent loop', 'plan-and-execute', '工具调用循环'],
  },
  {
    title: 'JavaGuide · MCP 与 Function Calling',
    url: 'https://javaguide.cn/ai/agent/mcp.html',
    summary: 'MCP 通过 Host、Client 和 Server 连接工具与数据。Function Calling 表达结构化调用意图，MCP 负责能力发现和通信，Agent 负责决定何时调用及如何使用结果。',
    terms: ['mcp', 'model context protocol', 'function calling', '工具接入', '工具发现'],
  },
  {
    title: 'JavaGuide · 本地缓存、分布式缓存与多级缓存',
    url: 'https://javaguide.cn/database/redis/cache-basics.html',
    summary: '本地缓存位于应用进程内，分布式缓存供多个实例访问。多级缓存按 L1、L2、数据库逐级读取；删除 Redis 缓存不会自动使其他 JVM 的本地副本失效。',
    terms: ['本地缓存', '分布式缓存', '多级缓存', 'l1', 'l2', 'caffeine'],
  },
  {
    title: 'JavaGuide · 三种缓存读写策略',
    url: 'https://javaguide.cn/database/redis/3-commonly-used-cache-read-and-write-strategies.html',
    summary: 'Cache Aside 由应用维护数据库与缓存，更新时先提交数据库事务再删除缓存。Read/Write Through 由缓存层负责同步访问存储，Write Behind 则异步写回。',
    terms: ['cache aside', '旁路缓存', '缓存一致性', '先更新数据库', 'write through', 'write behind', '缓存读写'],
  },
  {
    title: 'JavaGuide · Java 线程池原理',
    url: 'https://javaguide.cn/java/concurrent/java-thread-pool-summary.html',
    summary: 'ThreadPoolExecutor 通过核心线程数、阻塞队列、最大线程数和拒绝策略安排任务。shutdown 继续处理已提交任务；shutdownNow 尝试中断正在运行的任务并返回未开始的任务。',
    terms: ['线程池原理', 'corepoolsize', 'maximumpoolsize', 'shutdown', '阻塞队列', 'callerrunspolicy'],
  },
  {
    title: 'JavaGuide · Java 线程池使用实践',
    url: 'https://javaguide.cn/java/concurrent/java-thread-pool-best-practices.html',
    summary: '线程池实践包括设置队列容量、区分任务类型、监控积压和处理任务异常。Future.get 超时只结束调用方的等待，cancel(true) 和 shutdownNow 都依赖任务响应中断。',
    terms: ['线程池配置', '线程池监控', '有界队列', 'future.get', 'cancel(true)', '任务超时', '中断响应'],
  },
  {
    title: 'JavaGuide · MySQL 事务隔离级别',
    url: 'https://javaguide.cn/database/mysql/transaction-isolation-level.html',
    summary: '事务隔离级别规定并发读写的可见性，涉及脏读、不可重复读和幻读。READ COMMITTED 避免脏读，但仍可能出现不可重复读；InnoDB 的一致性读通过 MVCC 获取可见版本。',
    terms: ['脏读', '不可重复读', 'read committed', 'repeatable read', '读已提交', '读提交'],
  },
  {
    title: 'JavaGuide · MySQL 索引与覆盖索引',
    url: 'https://javaguide.cn/database/mysql/mysql-index.html',
    summary: 'InnoDB 使用 B+ 树组织索引，聚簇索引叶子保存行数据，二级索引叶子保存主键。查询所需列都位于索引中时可使用覆盖索引，减少回表读取。',
    terms: ['覆盖索引', '聚簇索引', '二级索引', '回表', '最左前缀', 'b+树', 'b+ 树'],
  },
  {
    title: 'JavaGuide · MySQL 执行计划字段',
    url: 'https://javaguide.cn/database/mysql/mysql-query-execution-plan.html',
    summary: 'EXPLAIN 的 type 描述访问方式，possible_keys 列出可能使用的索引，key 表示实际选择的索引。rows 是估算扫描行数，Extra 提供覆盖索引、排序等附加信息。',
    terms: ['possible_keys', 'key_len', 'using index', 'using filesort', 'explain字段', 'explain 字段', '估算行数'],
  },
  {
    title: 'JavaGuide · Spring 事务与代理',
    url: 'https://javaguide.cn/system-design/framework/spring/spring-transaction.html',
    summary: 'Spring 声明式事务通常通过 AOP 代理生效，同一类内自调用会绕过代理。默认回滚 RuntimeException 和 Error，检查型异常需要配置回滚规则；传播行为决定是否复用或新建事务。',
    terms: ['spring', '@transactional', '事务传播', '自调用', 'rollbackfor', 'requires_new'],
  },
  {
    title: 'JavaGuide · RabbitMQ 确认与死信交换机',
    url: 'https://javaguide.cn/high-performance/message-queue/rabbitmq-questions.html',
    summary: 'RabbitMQ 通过交换机和绑定把消息路由到队列。消费者完成处理后再手动确认；死信交换机 DLX 接收符合死信条件的消息，再按绑定路由到对应队列。',
    terms: ['rabbitmq', '手动确认', '死信', 'dlx', '交换机', 'routing key', '消息路由'],
  },
  {
    title: 'JavaGuide · 接口幂等与去重表',
    url: 'https://javaguide.cn/high-availability/idempotency.html',
    summary: '幂等设计通过业务唯一键、唯一索引和去重记录识别重复请求。去重表写入与业务操作应放在同一个数据库事务中，使业务失败时去重记录一起回滚。',
    terms: ['幂等', '去重表', '重复请求', '业务唯一键', 'idempotency', '消息去重'],
  },
  {
    title: '小林coding · 事务隔离级别的实现',
    url: 'https://xiaolincoding.com/mysql/transaction/mvcc.html',
    summary: '事务的原子性由 undo log 支持，隔离性通过 MVCC 或锁实现。Read View 根据事务可见性规则判断记录版本，undo log 版本链保存历史数据，二者共同支持一致性读。',
    terms: ['read view', 'readview', '版本链', '事务可见性', 'mvcc实现', 'mvcc 实现'],
  },
  {
    title: '小林coding · undo log、redo log 与 binlog',
    url: 'https://xiaolincoding.com/mysql/log/how_update.html',
    summary: 'undo log 属于 InnoDB，用于事务回滚和 MVCC；redo log 支持崩溃恢复与持久性；binlog 由 Server 层生成，主要用于数据备份和主从复制。',
    terms: ['undo log', 'redo log', 'binlog', '回滚日志', '重做日志', '归档日志'],
  },
  {
    title: '小林coding · Redis 过期删除与内存淘汰',
    url: 'https://xiaolincoding.com/redis/module/strategy.html',
    summary: 'Redis 组合惰性删除和定期删除清理过期键。内存淘汰由 maxmemory 限制触发，volatile 策略面向设置了过期时间的键，allkeys 策略面向所有键。',
    terms: ['惰性删除', '定期删除', '过期删除', 'maxmemory-policy', 'volatile', 'allkeys'],
  },
  {
    title: '小林coding · HTTP 强制缓存与协商缓存',
    url: 'https://xiaolincoding.com/network/2_http/http_interview.html',
    summary: '强制缓存通过 Cache-Control 或 Expires 决定是否直接使用本地资源。协商缓存使用 ETag、If-None-Match 或修改时间与服务器校验，资源未变化时返回 304。',
    terms: ['http缓存', 'http 缓存', '强制缓存', '强缓存', '协商缓存', 'etag', 'cache-control', '304'],
  },
  {
    title: '小林coding · TCP 重传与超时',
    url: 'https://xiaolincoding.com/network/3_tcp/tcp_feature.html',
    summary: 'TCP 通过重传处理数据丢失。RTT 表示数据发送到收到确认的往返时间，超时重传时间 RTO 需要随网络状况调整；RTO 过大延长等待，过小会引入不必要的重传。',
    terms: ['tcp', '重传', 'rtt', 'rto', '往返时间'],
  },
  {
    title: '小林coding · 进程状态与调度',
    url: 'https://xiaolincoding.com/os/4_process/process_base.html',
    summary: '进程是运行中的程序。运行态占用 CPU，就绪态等待调度，阻塞态等待 I/O 等事件；事件完成后由阻塞态转为就绪态，时间片用完时由运行态转为就绪态。',
    terms: ['进程状态', '进程调度', '就绪态', '阻塞态', '运行态', '时间片'],
  },
  {
    title: '小林coding · 进程间通信',
    url: 'https://xiaolincoding.com/os/4_process/process_commu.html',
    summary: '管道以字节流在进程间传递数据，pipe 返回读取端 fd[0] 与写入端 fd[1]。消息队列传递独立消息，共享内存将不同进程的虚拟地址映射到同一块物理内存。',
    terms: ['进程间通信', 'ipc', '匿名管道', '命名管道', '共享内存', 'fd[0]', 'fd[1]'],
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
