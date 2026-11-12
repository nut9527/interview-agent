# 架构与代码入口

职智达的后端按业务拆分为 Spring Boot 模块。岗位、简历或选定题目为面试提供上下文。模型负责出题与反馈，服务端决定可读取的材料、允许的动作和何时写入会话。

## 一次回答如何落库

1. 页面向 `POST /api/interviews/sessions/{id}/answer-commands` 提交回答、预期轮次 `expectedTurn` 和 `requestId`。后端受理后返回 HTTP 202 和任务 ID。同一账号用同一个 `requestId` 重试相同的作答命令，会取回原任务。若改了回答或轮次却复用该 ID，后端返回 HTTP 409。
2. 标准部署将任务和待发送的 Outbox 记录一起提交。发布器收到 RabbitMQ 的投递确认后更新记录。Worker 收到通知，从 MySQL 领取任务并取得执行租约。模型在数据库事务外运行，按需读取岗位或简历片段、回答原文、本场选定的知识资料。工具的返回内容随任务保存。
3. 服务端检查结构化输出、允许的路由和引用。模型提供来源、锚点和连续原文，服务端定位并生成引用偏移。校验失败时在任务预算内允许模型修正一次；仍不合法则任务失败，不提交候选评价。
4. 提交前再次检查任务归属、租约、轮次和资料使用资格。会话变更、任务终态和最终事件写入同一事务。浏览器通过 SSE 接收进度，重连时用 `afterSeq` 续读已保存的事件。若事件流反复连接失败，页面改为查询任务状态。任务成功后，页面重新读取会话，展示已保存的问答。

前端消费持久事件来更新执行进度和续读游标，正式问题在提交成功后从会话读取。[前端会话控制器](../frontend/src/pages/Interview/sessionController.ts) · [持久事件解析](../frontend/src/pages/Interview/runStream.ts)

面试执行采用异步命令接口：开场使用 `POST /api/interviews/start-commands`，训练任务使用 `POST /api/interviews/training-tasks/{id}/start-commands`。命令受理与模型执行分离，页面通过任务查询和事件订阅跟踪结果；会话查询、历史和删除使用独立接口。

`InterviewRunWorker` 调度上下文、模型、工具和校验四个阶段，分别由 `InterviewContextStage`、`InterviewModelStage`、`InterviewToolStage`、`InterviewValidateStage` 执行。`InterviewRunStore` 对外提供持久化运行接口；`RunAcceptance` 处理受理和幂等，`RunLease` 处理领取、续租与写入资格，`RunCommit` 在同一事务内保存业务结果和终态。`RunRepository` 负责查询及行映射，`RunRetention` 负责保留期清理，`RunTrainingTasks` 负责提交时关联训练任务。学习目标关联通过 `learning.api.GoalSessionLinks` 访问，由 learning 模块实现；关联写入加入已有事务。Java 类型依赖检查和 SQL 模块边界测试共同约束模块访问。

| 要看什么 | 从哪里读起 |
| --- | --- |
| 前后端从哪里启动 | [React 入口](../frontend/src/main.tsx)、[Spring Boot 入口](../backend/src/main/java/cn/edu/whu/zhizhida/ZhizhidaApplication.java) |
| 页面如何发起面试、处理断线 | [面试页面](../frontend/src/pages/Interview)、[API 客户端](../frontend/src/api) |
| 任务如何受理、取消、订阅 | [InterviewRunController](../backend/src/main/java/cn/edu/whu/zhizhida/interview/InterviewRunController.java) |
| Worker 如何领任务、保存检查点 | [interview/execution](../backend/src/main/java/cn/edu/whu/zhizhida/interview/execution) |
| 如何验证回答引文 | [evaluation](../backend/src/main/java/cn/edu/whu/zhizhida/evaluation) |
| 如何把能力目标转为训练任务 | [GoalPlanner](../backend/src/main/java/cn/edu/whu/zhizhida/learning/GoalPlanner.java) 通过工具生成草稿；[LearningGoalService](../backend/src/main/java/cn/edu/whu/zhizhida/learning/LearningGoalService.java) 在用户确认后创建任务 |

## 模型输出契约

生产只允许 `TOOL + A1` 和 `phased-v2`。`FIXED/LEGACY` 路由与 `B0` 仅用于 `test/it/agent-evaluation`；`S1/S3` 另需 `agent-evaluation`，实验配置禁止 API/Worker 分角色部署。升级前须结束或取消在途 Run，旧协议检查点不再恢复。

默认执行策略为 `A1`，最多6次模型请求、4次工具执行、120秒，保留最终生成与一次修正的预算。`phased-v2` 的 `ACTION` 阶段检查主张与证据，可提出一次 `REVISE` 调整取证，`COMPLETE` 后进入禁止工具调用的 `FINAL`。检查及修订历史随检查点保存；来源逐字核对，语义支持仍是模型判断。最终评价不能把检查中的未知项提升为确定结论，重大纠错须有已检查的资料反证。内置提示词为 `interview-prompts-v6`，字段契约为 `interview-output-v4`。[阶段协议](../backend/src/main/java/cn/edu/whu/zhizhida/interview/InterviewDecisionContract.java) · [字段契约](../backend/src/main/java/cn/edu/whu/zhizhida/interview/InterviewOutputContract.java)

新建非 `FORMAL` 会话（含专项训练）默认启用 `interview-agent-v6`：模型调整追问并在未覆盖能力中选路，每轮用 `focus` 指定主张、场景与期望证据，保存带回答引文的计划修订。同一追问策略可检查不同主张；相同能力、场景和主张的重复验证被拒绝。正式模拟保留原覆盖流程，已有会话沿用原版本。

学习目标使用独立 `GOAL_PLAN` 工具循环：检索已授权题目、提出或修订训练草稿，用户确认后建立任务。任务的验收要求冻结为 `InterviewTraining.objective`，进入训练上下文指导出题与追问。模型请求、用量和工具状态写入学习规划账本，跨次累计预算由目标控制。`autoAdjust` 默认关闭；用户开启后，每场完成更新观察，当前已确认批次练完才生成下一批待确认草稿，证据充分时等待本人确认目标完成。自动规划读取详细历史观察仍需开启学习观察个性化，未开启时只使用任务完成结果。暂停、取消和等待作答均不持续调用模型。计划 `focus` 保存未解决、已解决和冲突主张及观察来源，新证据可更新旧判断。[目标规划](../backend/src/main/java/cn/edu/whu/zhizhida/learning/GoalPlanner.java)

服务端生成可以确定的路由字段：评价中的 `questionCompetencyId` 从动作及目标能力派生；最后一轮的 `action=FINISH`、目标能力和 `probe=NONE` 由当前状态决定。非最后一轮仍由模型在允许范围内提出追问或推进。生成契约只展示当前轮需要的字段，未结束时生成 `nextQuestion`，结束时生成 `finalSummary`；已有会话及 API 继续保存完整领域结构。

评价使用六维 `assessment.detail`。每维先选择引文，再写一条被这些引文共同支持的具体观察；局部引文不足以概括整段回答。没有对照资料时，技术正确性保持 `NOT_OBSERVED`；没有备选方案及代价时，方案取舍也保持未知。未知项使用空引文与 `INSUFFICIENT`。提示要求反馈不超过 100 字、总结不超过 200 字，各维观察与限制尽量各 30 字；这些是生成建议，字段硬上限仍由契约校验。

引文必须能在授权来源的固定版本中逐字、唯一定位；回答证据不能省略拼接。服务端继续校验 JSON 完整性、字段关系、引用及资料撤销。引用覆盖结论的语义质量仍需结合原文复核，成功落库本身不代表评价正确。[输出解析](../backend/src/main/java/cn/edu/whu/zhizhida/interview/InterviewOutputCodec.java) · [评价提示](../backend/src/main/resources/prompts/interview/v1/assessment.txt)

## 数据与模型

- **材料快照**　开场时固定本场使用的岗位、简历、题目及知识内容。即使之后修改了材料，复盘也能对应到面试当时的原文。删除知识资料会撤销该来源的使用资格。
- **资料检索**　资料问答、自测选材和专项训练共用 BM25 与向量融合检索。标准 Docker 内置 BGE 和 pgvector，在已授权的固定版本内融合两路排名，向量命中核对完整归档，不受 SQL `LIKE` 候选池限制。已启用的向量服务异常直接报错。未限定资料的全库推荐使用词项，自测支持明确片段选择与无重点全文采样。面试工具按全局排名和正文预算截取片段，再按文档整理引用。在工具调用模式下，若已选资料有正文但都没有初始摘录，Worker 会在模型调用前按本轮问题检索一次，保存回执并计入工具预算。[资料选择](../backend/src/main/java/cn/edu/whu/zhizhida/knowledge/TrainingKnowledgeCatalog.java) · [混合检索](../backend/src/main/java/cn/edu/whu/zhizhida/knowledge/HybridKnowledgeSearch.java)
- **模型适配**　`ai` 模块封装 DeepSeek 请求、工具调用和用量处理。面试、简历优化和知识问答分别定义业务提示词和结构校验。Mock 模式仅在本机演示流程，不代表真实模型的规划与评价效果。
- **LangChain4j 集成**　SDK 负责模型协议接入，应用负责供应商字段适配、原始消息回放、工具约束和 usage 校验。模型调用统一接收完整 JSON 响应；工具执行、记忆和重试由应用运行时统一管理。
- **跨场能力观察**　轮次、能力及维度持久化到 MySQL 的 `learning_observations`，会话 JSON 保存展示和复盘快照。`LEARNING_PRACTICE_TOOLS_ENABLED` 默认开启，用户的历史个性化偏好默认关闭。用户允许后，新命令从近 180 天、同执行模式和兼容评价标准的历史中冻结最多 20 条引用，按能力、维度和场次分配名额。工具读取与提交时重查来源版本；纠正、删除和来源撤销会使相关旧引用失效。历史汇总最多读取最近 1000 条，与练习工具的召回窗口分开。[记忆选择](../backend/src/main/java/cn/edu/whu/zhizhida/learning/LearningPracticeMemory.java) · [召回排序](../backend/src/main/java/cn/edu/whu/zhizhida/learning/PracticeMemoryRecall.java)
- **检索片段如何进入面试评分**　Worker 从本轮已保存的知识检索回执中取出片段，核对所选文档的版本、哈希、片段编号、行范围和完整原文。通过核对的片段可在本轮来源目录的剩余预算内取得引文编号，模型生成和提交校验共用这份目录。被裁剪的片段可供阅读，评分时只接受目录内能逐字定位的引用。

接口路径与字段见 [OpenAPI 定义](../contracts/openapi.json)，表结构见 [Flyway 迁移](../backend/src/main/resources/db/migration)。

TypeScript 契约生成器复用 `ApiResponse<T>` 和统一错误码类型。知识库、题库与岗位的前端类型由生成 Schema 派生，界面层仅补充历史快照的空值、默认值及展示枚举；答题请求直接使用 run Answer 契约，必须包含请求编号和预期轮次。

## 中间件协作

RabbitMQ 负责异步任务通知；两个 Redis 分别承担题目快照缓存，以及提交频控、共享 AI 配额、熔断、SSE 许可和清理租约；MCP 提供只读技术资料检索。标准部署下，在「精选题目」打开「消息重复消费与业务落库」，查看解析、开始练习并提交回答，即可走通这些链路。Mock 模式使用相同的业务接口和中间件，学习记忆的持久数据保存在 MySQL。

| 用户动作 | 处理流程 | 设计说明 |
| --- | --- | --- |
| 查看解析和延伸阅读 | 后端将已发布题目的标题和标签交给只读 MCP 工具。资料服务在本地目录检索，返回 RabbitMQ 消费确认与发布确认的官方文档链接和阅读提示。面试 Agent 也能通过同一工具查资料。 | 同一份资料目录可供题库和模型使用。资料范围由服务端维护，目录未收录的主题可能没有结果。资料服务故障时用户仍能读题和查看原解析。 |
| 开始练习、重复练习 | 后端核对发布状态后，按题号和版本从缓存 Redis 读取不可变快照。缓存缺失或失效时从 MySQL 归档取正文。新的开场与作答命令还要经过独立的频控 Redis，Lua 脚本原子检查计数和剩余时间。 | 题库缓存允许淘汰，频控实例使用 `noeviction`，避免缓存压力清掉限流计数。频控故障时新命令返回 503；超限返回 429 和 `Retry-After`。已受理的相同请求可以取回原任务。 |
| 提交回答，等待本轮问题 | API 返回任务 ID，Outbox 发布任务。Worker 无容量、退避等待或执行失败时，先将原消息发布到持久 quorum 重试队列并等待确认，再 ACK；2 秒 TTL 后按至少一次语义转回执行队列。 | MySQL 保存检查点和租约，终态与活跃租约使重复通知直接结束；重试不重置模型预算。数据库扫描保留崩溃和超时恢复职责。资料上传也通过队列处理，原文件保存在共享目录。 |

代码可从[只读资料服务](../services/mcp-reference/server.mjs)、[题目快照缓存](../backend/src/main/java/cn/edu/whu/zhizhida/questionbank/QuestionSnapshotCache.java)、[频控脚本](../backend/src/main/resources/redis/ai-submit.lua)和[面试任务消费者](../backend/src/main/java/cn/edu/whu/zhizhida/interview/execution/RunDispatchConsumer.java)查看。要复现缓存回源、跨 API 限流和消息重投，可在具备本地测试镜像、MySQL 工具及 Maven 依赖的机器上执行 `node scripts/verify-middleware-live.mjs --offline`。[业务集成断言](../backend/src/test/java/cn/edu/whu/zhizhida/middleware/MiddlewareApplicationIT.java) · [知识导入重投断言](../backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeMqIT.java)

中间件集成测试使用两个独立的 Redis 7.4.2、RabbitMQ 4.1.0 和临时 MySQL，覆盖跨 API 实例的限流窗口、幂等请求重放，以及落库后 ACK 丢失触发的消息重投，核对任务、会话事件和配额记录。MCP 验收通过 Mock 模型的题库 HTTP 链路检查资料检索、鉴权和服务中断行为：资料服务关闭后，延伸阅读返回 503，题目详情返回 200。在 `backend/` 执行 `mvn package -DskipTests`，准备前端依赖和 MySQL 工具后，到项目根目录运行 [`node scripts/verify-mcp-reference-live.mjs`](../scripts/verify-mcp-reference-live.mjs)。

## 验证入口

| 检查内容 | 运行方式 |
| --- | --- |
| 代码格式 | CI 只查相对基线变更的源码。在 `backend/` 用 `mvn spotless:apply "-Dformat.base=<基线提交>"` 修正 Java 格式，在 `frontend/` 用 `pnpm exec prettier --write <文件>` 修正前端格式。 |
| 后端单元测试 | 在 `backend/` 执行 `mvn test` |
| 专项训练检索对照 | 在 `backend/` 执行 `mvn test "-Dtest=KnowledgeHybridRetrievalEvaluationTest"` 得到离线词项基线。启动本机 BGE 后，追加 `"-Dknowledge.eval.embeddingModel=bge-small-zh-v1.5-q" "-Dknowledge.eval.embeddingPort=18092" "-Dknowledge.eval.embeddingDimension=512"` 同时评测混合检索。 |
| 检索到答案的证据判断 | [KnowledgeAnswerAcceptanceTest](../backend/src/test/java/cn/edu/whu/zhizhida/knowledge/KnowledgeAnswerAcceptanceTest.java) 用真实 BGE、生产检索器和回答器检查改写、比较、冲突及无答案题；显式启用后最多 10 次真实生成，每次输出上限 1500 token。环境参数见[运行指南](启动与故障排查.md#验收入口)。 |
| Redis 与 MQ | `node scripts/verify-middleware-live.mjs --offline` 检查缓存、共享限流和幂等；`node scripts/verify-run-retry-live.mjs` 检查关闭恢复扫描后的 Broker 重试、断路与重启。均使用隔离依赖和 Mock 模型。 |
| 记忆持久化与撤销 | `node scripts/verify-backend.mjs --offline` 使用临时 MySQL 运行集成测试，其中 [LearningMemoryIT](../backend/src/test/java/cn/edu/whu/zhizhida/learning/LearningMemoryIT.java) 和 [PracticeMemoryRunsIT](../backend/src/test/java/cn/edu/whu/zhizhida/learning/PracticeMemoryRunsIT.java) 覆盖偏好、召回、更正、删除及任务提交。 |
| 真实模型 Worker | `node scripts/verify-provider-lifecycle.mjs --prepare` 只检查准备条件。显式付费执行参数见[运行指南](启动与故障排查.md#验收入口)；报告保留每次响应、用量、校验结果及最终会话，需区分被拒候选与已提交评价。 |
| 前端类型及单元测试 | 在 `frontend/` 执行 `corepack pnpm install --frozen-lockfile`，再执行 `corepack pnpm typecheck` 和 `corepack pnpm test`。测试使用 Node.js 22.14 或更高。 |
| 接口契约 | 后端单元测试中的 [OpenApiContractTest](../backend/src/test/java/cn/edu/whu/zhizhida/contract/OpenApiContractTest.java) 核对实际路由与 OpenAPI 快照。在项目根目录执行 `node scripts/api-contract.mjs check` 可核对快照与生成的 TypeScript 类型。 |
| 本地运行检查 | 以 Mock 模式启动服务后，在根目录执行 `node scripts/verify-startup.mjs http://localhost`。源码启动时将地址替换为 `http://127.0.0.1:5173`。 |

检索回归集包含 5 份合成笔记和 30 道标注题，用于比较词项与混合检索的排序结果。报告提供 Recall@k、MRR、nDCG@k，并按 direct/synonym/paraphrase/multi/distractor/insufficient 分组。标注单位是文档与章节，同一章节的重复片段只获得一次相关性收益；无答案题单独报告空检索率，由回答层判断候选证据是否充分。`p95Micros` 为预热后的查询耗时（微秒），混合模式包含本机嵌入请求及内存向量搜索，索引构建、数据库和生成耗时另计。离线评测随普通 `mvn test` 进入 CI，JSON 报告随后端测试产物保存；真实向量对照需显式提供本机嵌入服务参数。端到端质量验收使用独立的[发布标注口径](../contracts/agent-release-criteria.v1.json)。

## RAG 配置与引用规则

标准 Docker 默认启用 BGE 512 维嵌入与 pgvector；源码环境由 `enable-rag.mjs` 准备，直接运行后端的 `AGENT_VECTOR_ENABLED` 默认是 `false`。LangChain4j 接入嵌入接口及向量存储，BM25 与向量结果通过 RRF 融合。向量最低分 `AGENT_VECTOR_MIN_SCORE` 默认 0.55；已启用的向量服务失败返回服务不可用，不隐式切换为词项检索。

嵌入文本为章节标题加换行加正文，词项、向量索引和融合结果统一排除纯标题块；自测显式选中纯标题片段时提示重新选择正文。归档正文、分块 ID 和引用偏移保持稳定；预处理指纹变化时，索引任务生成新代际，待其就绪后替换旧代际。只有标题的文档清理索引并结束任务。单个资料版本的向量索引上限为 4096 个有效片段，业务正文上限为 20000 个片段；超出向量上限的资料需拆分后建立索引。

融合参数可通过 `AGENT_RETRIEVAL_RRF_K`（默认 60，范围 1..1000）、`AGENT_RETRIEVAL_LEXICAL_TOP_N` 和 `AGENT_RETRIEVAL_VECTOR_TOP_N`（默认 30，范围 1..100）设置。`AGENT_RETRIEVAL_MAX_PER_HEADING`（默认 2，范围 1..20）让不同章节先获得名额，再用剩余候选补齐；无标题片段按各自编号分组。检索配置进入工具回执指纹，调整排序不触发重新嵌入。混合评测接受对应的 JVM 参数，例如 `-Dagent.retrieval.rrf-k=60`、`-Dagent.retrieval.vector-top-n=30`，报告保存完整指纹。测试参数通过 JVM 传入，测试进程不读取 `.env`。

回答提示要求结论、证据和适用边界，支持一个结论引用多个片段，并分别说明相互矛盾的资料。追问在承接或指代时补入前文问题，检索依据来自用户问题和已归档资料。检索有候选不等于资料足够：回答器在同一次生成中判断问题所需证据，资料不足时返回 `sufficient=false` 和空 `claims`。可能原因与现场诊断分开；资料未提供的数值、步骤和结果不能补写。服务端校验引用原文与范围，语义支持度通过对照原文复核和「未解决 / 引用不支持 / 漏掉资料」反馈核对。面试正文预算以 UTF-16 单元计量，模型调用预算另行统计 token 用量。
