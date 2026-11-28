# 验证与回归数据

本目录保存面试回放、工具调用、知识检索和向量存储的验证数据。报告按场景和批次组织，包含测试条件、实际结果、模型用量及源码指纹，供回归测试与发布核对使用。

[分块对照](chunking-comparison.md)使用当前 91 道题。全库词项与混合的引文 Recall@6 分别由 78.13%、75.63% 升至 89.38%、85.63%。

[性能复测](performance-retest.md)记录历史缓存实验和当前 25 份资料的三轮检索。下面的 5 份合成资料来自另一组历史实验。

[检索诊断](retrieval-candidate-diagnostics.md)在选定范围和全库分别发现 10、14 道题的标注引文进入 RRF 前 30，却未进最终 6 块。

[重排实验](reranker-experiment.md)保留旧语料的整集重排指标，并记录当前语料 182 组排名核对与单题真实重排。

面试回放测试读取已录制的模型请求、应答和运行结果，校验原始文件的 SHA-256 后复现历史输出、修正失败和原文引用检查；历史裸输出不会重新进入生产 Worker。[回放用例](../../backend/src/test/java/cn/edu/whu/zhizhida/interview/CloseoutRecordedProtocolTest.java) · [录制文件与校验值](../../backend/src/test/java/cn/edu/whu/zhizhida/interview/CloseoutRecordedFixtures.java)

## 运行专项结果

当前实现的专项结果集中在[Agent 与中间件运行验收](runtime-acceptance.md)：真实模型单轮生命周期及最终引用复核通过，前端与 MCP 容器检查 11 项通过，面试相关回归测试 276 项通过。后端、BGE、前端和 MCP 镜像已构建，真实 BGE 推理与 pgvector 查询已验证。

RAG 回答层的 5 个无答案样本正确拒答，误拒修正后的 2 个定向案例通过。该结果与下述检索排序实验分别统计；非空检索候选不代表资料足以回答。真实模型专项覆盖一个合成单轮案例，包含一次正常输出修复及独立 Agent 语义复核。

## 发布状态与验收边界

当前发布登记为 **`CANDIDATE_NOT_ACCEPTED`**，源码尚未最终冻结。仓库提供可展示的候选版本；已有记录不能支持“完整 AI 质量验收通过”或“生产成熟”的结论。以下状态来自已保存证据，不代表在当前代码上重新执行了所有历史实验。

| 范围 | 当前证据 | 能证明的内容与限制 |
| --- | --- | --- |
| 单元测试、契约、Mock 全栈及浏览器检查 | [Verify 工作流](../../.github/workflows/verify.yml) 与对应运行报告 | 验证业务、持久化和交互行为；Mock 或录制回放不能衡量真实模型的语义质量。工作流定义本身不是执行通过记录 |
| 真实模型工具与引文专项 | [引文修复记录](langchain4j-worker-citation-fix.json)为 `PASSED`，覆盖 1 个隐藏初始摘录的场景 | 证明该次服务端检索与引文映射成功；不代表模型能自主选择正确工具，也不代表所有样本通过 |
| 真实模型失败记录 | [收尾台账](closeout-model-budget.jsonl)为 `FAIL`；[预载材料](langchain4j-worker-baseline.json)、[模型自行选工具](langchain4j-worker-model-choice.json)、[检索后引文](langchain4j-worker-retrieval.json)对照未通过 | 属于对应候选与实验条件的结果；与 v5 单轮专项分别保留，不能合并为同一候选的成功率 |
| 完整发布验收 | [登记](../../contracts/release-v1.0.json)包含 12 个样本及独立的 6+3 连续流程，候选与流程输入尚未冻结，后续执行器工作仍未完成 | 尚无完整最终候选的结果和逐项人工检查，不能宣布发布通过 |
| 策略泛化与留出集 | [策略台账](kernel-model-budget.json)为开发分区实验，`heldoutExecuted=false` | 不提供留出集表现或泛化效果结论；[内核质量标准](../../contracts/agent-release-criteria.v1.json)要求的最终评测是另外的验收范围 |
| 知识检索 | 下述 25 条正例与 5 条无答案合成题的检索对照 | 只衡量特定数据与环境的召回和排序；混合检索无答案空检索为 `0/5`，不能据此宣称拒答可靠或答案正确 |

**只读状态检查。** 在项目根目录执行：

```sh
node scripts/release-status.mjs check
node --test scripts/release-status.test.mjs
```

检查器核对冻结登记与历史预算台账哈希链，输出历史批次、专项结果及尚未完成的登记工作。`check` 返回 0 只表示状态清单检查通过，输出仍为 `releaseAccepted: false`；它不重新评判模型答案，也不认证所有专项文件的内容。`node scripts/release-status.mjs require-accepted` 是拒绝把当前候选当作已验收版本的门禁，当前应返回非零退出码。检查器只支持现有候选登记；未来发布状态变化时，必须同时补齐验收证据及验证实现，不能只更改状态字符串。

**完成真实 AI 发布验收的条件。** 按登记顺序完成以下工作，并保留历史记录：

1. 补齐登记中的执行器工作：6+3 HTTP 流程和输入冻结、完整 12 样本（含派生样本）接入、后续批次证据与检查入口。冻结最终源码清单、模型可用性、提示词及配置指纹；不同候选的结果分别标识。
2. 在已明确的模型调用授权和预算范围内，核对供应商可用模型与有效价格；保留逐次请求、响应、用量及未知用量预占。默认 CI 和 Mock 浏览器测试不调用真实模型，也不提供此项授权。
3. 在同一最终候选上完成登记的批次、12/12 样本，以及独立的 6 轮面试接 3 轮复练。失败、取消、超时和未运行项全部列入结果；工具回执、来源版本、引文范围、会话关联和重试行为均可定位。
4. 按[逐项检查协议](../../scripts/closeout-review.mjs)与[检查表模板](../../scripts/fixtures/review-template.json)记录引用支持、追问相关性、重复提问、无依据纠错和复练衔接。模板本身不是完成记录；检查须注明人员、方法、时间和证据位置，没有独立评审时标为开发者人工检查。当前单轮检查器不能代替 6+3 流程验收。
5. 解决阻断问题，重跑受影响样本并完成登记要求的最终回归；核对完整证据、预算和检查表后再形成新的发布决定。若要宣称内核泛化质量，还须另外满足内核质量标准中的数据规模、留出集、人工评审和不确定性报告要求。

## 数据索引

| 数据 | 用途 |
| --- | --- |
| [Agent 与中间件运行验收](runtime-acceptance.md) | v5 真实 Worker、引用复核、RAG 回答、消息故障与容器构建的结果和复跑入口 |
| [缓存证据复核](cache-benchmark.md)与[审计结果](cache-benchmark-audit.json) | 从历史原始样本重算缓存统计，说明命中率、正文回源与 DB 查询的区别。本次 RocketMQ 批次对照与暂停领取后的恢复数据见[派发复测](performance-retest.md#rocketmq-面试任务派发) |
| [向量与章节配额消融](retrieval-ablation.md)与[逐题结果](retrieval-ablation.json) | 词项基线上的四组独立开关对照；本次固定集的配额增量为 0，不能将混合检索整体收益归因于配额 |
| [Agent 质量评测准备](agent-quality.md)与[覆盖汇总](agent-quality-smoke/summary.json) | 冻结 30 个非保留家族，提供人工盲评入口；目前仅有 6/90 条路线输出、0 条人工评分，不能宣称质量提升 |
| `closeout-1-*.plan.json`、`closeout-1-*.wire.jsonl` | 该批次的执行计划，以及逐次录制的模型请求与应答 |
| `closeout-1-*.jsonl`（运行报告）、`*.jsonl.audit.json` | 各场景的任务状态、工具回执，以及模型调用次数核对结果 |
| [closeout-model-budget.jsonl](closeout-model-budget.jsonl) | 预算和批次验收台账。批次 `closeout-1` 的两个场景均执行完成；`redis-order` 的工具回执检查失败，批次结果为 `FAIL` |
| [策略对照台账](kernel-model-budget.json) 与 `kernel-real-*.jsonl` | 策略 B0、S1、S3 在开发分区样本上的运行结果、调用用量和预算预占；模型调用前失败的批次记入台账。执行范围为开发分区，留出分区状态为未执行 |
| `agent-evaluation-*.jsonl` | 对比固定流程和工具调用流程 |
| [pgvector-acceptance.json](pgvector-acceptance.json) | 使用真实 pgvector 和模拟嵌入服务，验证索引持久化、版本隔离与删除 |
| [中文检索对照基线](knowledge-retrieval-bge.json) | 正文嵌入策略的检索基线，用于与标题加正文嵌入策略对照；包含逐题结果、无答案题空检索率及查询耗时 |
| [BGE 检索评测](knowledge-retrieval-bge-current.json) | 标题加正文嵌入策略，使用本地 BGE 和内存向量存储。评测集含 25 条正例与 5 条无答案合成题，指标见下表 |
| [本地混合 RAG 运行记录](hybrid-live.json) | 对应版本中 P01 词项检索返回 0 条，混合检索返回 4 条并命中写后读原文；包含当时的向量故障回退行为。当前实现对已启用向量服务的故障直接报错，该记录不用于证明当前故障行为 |
| [供应商连接诊断](provider-business-diagnostic.json) | 模型列表查询与 TLS 连接诊断，推理调用次数为 0；模型可用性按报告中的配置和返回列表核对 |

## 知识检索指标

检索评测以文档和章节为标注单位，正例指标取 25 条有答案题的宏平均，无答案题单独计算空检索率。执行环境为本地 BGE 模型与内存向量存储，统计范围为检索结果。

| 指标 | 词项检索 | 混合检索 |
| --- | --- | --- |
| Recall@6 | 0.960 | 1.000 |
| MRR@6 | 0.933 | 0.973 |
| nDCG@6 | 0.940 | 0.980 |
| 无答案题空检索率 | 1/5 | 0/5 |

回答层负责判断候选片段是否足以支持结论，并校验引用的文档版本和原文位置。检索评测与答案正确性、引用支持率分别验收，标注规则见[发布质量标准](../../contracts/agent-release-criteria.v1.json)。

## Worker 工具调用对照

工具调用对照使用 `deepseek-flash` 和合成面试材料。每份摘要的 `runs[].report` 指向运行明细，同名 `.audit.json` 记录持久化台账中的调用次数。

| 对照条件 | 结果 |
| --- | --- |
| [预载材料基线](langchain4j-worker-baseline.json) | 两个场景均完成。`adequate-owner` 使用预载材料直接回答，通过验收；`redis-order` 的必需工具回执检查失败，批次结果为 `FAILED` |
| [隐藏 Redis 初始摘录，由模型选择工具](langchain4j-worker-model-choice.json) | 模型完成回答，缺少必需的检索回执，验收失败 |
| [隐藏摘录，服务端先检索](langchain4j-worker-retrieval.json) | Worker 取回相关片段。提交的引文编号未通过来源校验，任务失败 |
| [服务端检索与引文目录映射](langchain4j-worker-citation-fix.json) | Worker 检索一次，模型调用一次。提交的一条知识引文与文档版本和原文偏移匹配，验收通过 |

四组对照共执行五个场景实例，合计六次推理、6,059 个输出 token，包含失败尝试。通过项覆盖隐藏初始摘录时的服务端检索与引文映射；预载材料场景的工具回执检查状态见基线报告。

完整发布验收按[发布登记](../../contracts/release-v1.0.json)执行，覆盖 12 个登记样本及连续面试、复练场景。登记状态为 `CANDIDATE_NOT_ACCEPTED`，上述专项记录用于核对各检查项的实际结果。

## 界面示例

[复盘推荐](interview-learning-desktop.png) · [个人训练计划](interview-plan-mobile.png)
