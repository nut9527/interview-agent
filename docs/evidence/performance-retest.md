# RocketMQ、Redis、RAG 与 MCP 性能复测

## RocketMQ 面试任务派发

2026-11-28 在 Windows 11、i7-14650HX、24 个逻辑处理器、31.7 GiB 内存、JDK 21.0.12 上串行测量两个批次。单个后端进程运行 Mock 模型，使用独占 MySQL 8.0.46、Redis 7.4.2、RocketMQ 5.3.2 和 Java SDK 5.2.2。每 500 ms 启动一次 Outbox 发布，单批预算 100 ms，逐条等待回执。两个消费者每次各收一条，等待窗口 200 ms，业务排队上限 10 秒。

| Outbox 每批上限 | 突发任务完成 | 吞吐量（任务/秒） | 排队 P95 | 端到端 P95 | 恢复领取至积压 6 项全部完成 |
| --- | ---: | ---: | ---: | ---: | ---: |
| [1 条](rocketmq-benchmark-batch1.json) | 36/36 | 1.92 | 6125 ms | 6171 ms | 623 ms |
| [8 条，默认](rocketmq-benchmark-batch8.json) | 36/36 | 7.11 | 1573 ms | 1611 ms | 722 ms |

每组预热 4 项，再提交三轮、每轮 12 项。吞吐量用 36 项除以三轮墙钟耗时之和，P95 从合并的 36 个样本取最近秩。恢复实验另行暂停 Worker 领取，等 6 项完成发布并确认仍为 `ACCEPTED` 后恢复领取。批次 8 在本机持续突发时缩短了排队时间，两次恢复实验各有一次样本。

在项目根目录执行下面的 PowerShell 命令可复测。每次运行使用新的隔离前缀，生成的样本位于对应的 `backend/target/middleware-live/<前缀>/middleware-benchmark.json`。

```powershell
$env:BENCHMARK_PUBLISHER_BATCH_SIZE='1'
node scripts/verify-middleware-live.mjs --offline --benchmark
$env:BENCHMARK_PUBLISHER_BATCH_SIZE='8'
node scripts/verify-middleware-live.mjs --offline --benchmark
```

## Redis 与应用缓存

历史缓存实验使用独占 MySQL 8.0.46 和 Redis 7.4.2。

题目 `qb-java-001` 预热 10 次后，串行测量冷、热缓存各 50 次。冷组删除缓存键的时间不计入 Catalog 调用耗时。

| 指标 | 冷缓存 | 热缓存 |
| --- | ---: | ---: |
| Catalog P50 | 3.91 ms | 1.60 ms |
| Catalog P95 | 7.21 ms | 3.29 ms |
| 缓存命中 | 0/50 | 50/50 |
| 正文加载次数 | 50 | 0 |

热缓存 Catalog P95 降低 54.44%。每次调用仍检查 MySQL 中的发布状态和版本，正文加载为零不等于 SQL 查询为零。另测 20 次热缓存创建训练来源 HTTP 请求，P95 为 **14.09 ms**。

Redis 命令基准在独立容器内通过回环 TCP 执行，单个热键、256 字节值、pipeline=1、关闭 RDB/AOF；每个操作、每种并发测 10 万次，重复三轮。

| 客户端并发 | SET 吞吐 | GET 吞吐 |
| --- | ---: | ---: |
| 1 | 23,472 次/秒 | 23,594 次/秒 |
| 32 | 178,784 次/秒 | 185,529 次/秒 |

120 万次计时命令无错误，60 万次 GET 全部命中。这组容器内命令吞吐与上述经过宿主机网络、Java、MySQL 的 Catalog 延迟分别统计。

原始记录见[缓存计时](cache-retest.json)和[Redis 命令基准](redis-throughput.json)。

## RAG 检索

2026 年 11 月 28 日，以 25 份资料、889 个当前分块和 91 道题运行了三轮。每题最多选 5 份资料。80 道有标注引文的正例参与质量评分，另有 11 道资料不足题。每种模式记录 273 个暖态查询耗时。质量指标按独立题目计算，命中单位为标注章节。[逐题结果、模型指纹与运行环境](reference-retrieval-bge.json)

| 模式 | 章节 Recall@6 | MRR@6 | nDCG@6 | P50 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 词项 | 91.25% | 0.8004 | 0.8148 | 3.26 ms | 12.48 ms |
| 词项与 BGE 融合 | 92.50% | 0.7654 | 0.7864 | 13.17 ms | 28.58 ms |

本地 BGE 输出 512 维向量，混合路线使用内存向量检索与 RRF。计时包含本机查询嵌入和排序，在资料范围筛选后开始。SQL、建索引和回答生成另计。11 道资料不足题在两条路线上均返回候选。完整引文的命中情况见[候选诊断](retrieval-candidate-diagnostics.md)。

旧版 27 份资料、103 道题的历史整集实验曾测得词项与混合章节 Recall@6 分别为 93.33%、89.44%。语料与分块均已调整，两次实验按各自的输入解释。

项目根目录运行 `node scripts/evaluate-reference-retrieval.mjs` 可生成新的三轮报告。

## MCP HTTP

目录更新后的 HTTP 计时、查询命中及复跑命令见[MCP 实测](mcp-http.md)。
