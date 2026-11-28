# 题目快照缓存证据与复核

简历中的 `4.97 ms / 2.94 ms` 有历史原始样本可核对，但它们来自单机、单题、串行冷暖对照，不代表生产性能。正式版优先使用结构性指标：**本地冷、热缓存各 50 次对照，缓存命中率由 0% 到 100%，正文回源由 50 次到 0 次；实时发布状态与版本检查仍保留。** 这里的 100% 是人为热缓存条件下的结果，不是实际业务流量的命中率。

## 原始记录

- 原始测量使用真实 MySQL、Redis，本地单进程，模型为 mock；文件位置与内容指纹见[审计结果](cache-benchmark-audit.json)。
- [本次审计](cache-benchmark-audit.json)：重新计算 100 个原始耗时的统计量，核对原始文件 SHA-256、计数约束及比率。**本次没有重新运行中间件压测。**
- [缓存样本](cache-baseline.json)保留原始冷热计时、命中计数及原始记录 SHA-256。

| 指标 | 冷缓存 | 热缓存 | 证据性质 |
| --- | ---: | ---: | --- |
| 串行调用数 | 50 | 50 | 原始耗时数组长度 |
| 快照缓存命中数 | 0 | 50 | 原记录的 Micrometer `hit` 计数器增量 |
| 快照正文回源数 | 50 | 0 | 原记录的 Micrometer `source` 计数器增量 |
| 命中率 | 0% | 100% | 命中数除以调用数，本次重算 |
| Catalog P95 | 4.9700 ms | 2.9393 ms | 原始耗时排序后第 `ceil(50 × 0.95) = 48` 项，本次重算 |

命中与回源增量由原集成测试采集并断言；历史文件没有逐请求 SQL 跟踪，审计不能独立重建这两个增量。耗时可以从每组完整的 50 个原始样本重算，`2.94 ms` 是 `2.9393 ms` 保留两位小数。原记录没有绑定精确执行提交的签名证明；审计列出的归档源码哈希用于追溯已有归档，不能把它解释为执行时源码认证。

## 测量口径

历史测试位于归档的 `MiddlewareBenchmarkIT.cacheSamples`，当前入口已整合到 [SplitRuntimeIT.Benchmark.cacheSamples](../../backend/src/test/java/cn/edu/whu/zhizhida/middleware/SplitRuntimeIT.java)。先执行 10 次预热，再固定题目 `qb-java-001` 与同一版本：

1. 冷组每次先删除该题的版本化快照键，再计时调用 `QuestionCatalog.requirePublishedForExecution`；删除键的时间不计入耗时。50 次均包含 Redis 未命中、MySQL 正文回源及缓存写入。
2. 热组复用冷组最后一次填充，连续读取 50 次，原测试断言 `source` 增量为 0、`hit` 增量为 50。
3. [DefaultQuestionCatalog](../../backend/src/main/java/cn/edu/whu/zhizhida/questionbank/DefaultQuestionCatalog.java) 每次先读取发布状态与版本，随后才访问快照缓存。[MybatisPlusQuestionRepository](../../backend/src/main/java/cn/edu/whu/zhizhida/questionbank/MybatisPlusQuestionRepository.java) 的 `state` 与 `snapshot` 是不同查询。因此“正文回源为 0”不等于“数据库查询为 0”，也不能据此报告 DB QPS。
4. [QuestionSnapshotCache](../../backend/src/main/java/cn/edu/whu/zhizhida/questionbank/QuestionSnapshotCache.java) 的 `source` 在调用正文加载器前递增；此计数代表加载尝试。原测试无错误且每次成功，才能在本次记录中对应成功正文回源。它不是 SQL 拦截器计数。

该计时范围是 Catalog 调用，不能写作 HTTP P95；`GET /questions/{id}` 不使用此缓存。原文件另含热缓存下创建训练来源的 HTTP 测量，不能将其与 Catalog 耗时混用。冷暖对照不能量化 TTL 抖动或并发回源合并各自的收益，也不是并发容量压测。

## 复核与重跑

在项目根目录执行，只读审计不访问服务、`.env` 或模型：

```sh
node scripts/audit-cache-benchmark.mjs
node --test scripts/audit-cache-benchmark.test.mjs
```

审计测试覆盖缺失样本、篡改尾部耗时、错误分位数、错误命中率、错误回源数及负耗时的拒绝。`node scripts/audit-cache-benchmark.mjs --write` 可重新生成审计摘要与当前源码指纹，保留原始历史记录。

需要在当前代码上重新采样时，已有 [真实中间件验证脚本](../../scripts/verify-middleware-live.mjs) 提供入口：

```sh
node scripts/verify-middleware-live.mjs --offline --benchmark
```

前提是本机已有 `redis:7.4.2`、`apache/rocketmq:5.3.2` 镜像、Docker、MySQL 工具和 Maven。脚本使用临时 MySQL 与独立中间件容器，回环随机端口，不读业务 `.env`、不调用付费模型；新结果写入 `backend/target/middleware-benchmark.json`。重跑会创建 Docker 资源，须在允许启动本地中间件的环境中执行。本次仅恢复并审计历史记录，没有启动此流程。按独立运行编号保留新样本，勿覆盖旧记录；现有源码与归档存在差异，不能将历史测量冒充当前版本重测结果。
