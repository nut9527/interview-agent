# 当前架构

更新：2026-10-26。产品只保留模拟面试及其材料、账号基础功能。

## 模块

| 模块 | 职责 |
| --- | --- |
| auth / application | Token、用户、演示空间、密码及注销；application 通过各模块的管理 API 编排清理 |
| resume | 简历 CRUD、解析、版本/比较/恢复、表达优化；前端列表、编辑、导入和预览分别组织 |
| jobs | 仅本人私有目标岗位及要求提取/原文定位，无公共目录、采集、收藏、推荐或刷新任务 |
| interview | 材料快照、难度、计划、动态追问、回答幂等/租约、历史与复盘 |
| ai | 通用 JSON 模型协议、用量、并发保护和熔断；业务 Prompt 属于 resume/interview |

跨模块只访问公开 `api`。ArchUnit 约束依赖方向、事务编排和纯规则；domain 不访问数据库、HTTP 或身份设施。

## 面试

前端 `/interview` 默认打开材料准备。保存岗位与本场自定义 JD 二选一；目标岗位读取本人当前 revision，简历读取本人已保存内容。直接携带 `resumeId/targetJobId` 的链接进入新场准备，不恢复上一场会话。

`InterviewStateMachine` 固定简历、JD、模式、总轮次和难度。`InterviewAgentPolicy` 从材料选择能力计划并约束追问/推进/结束。模型读取服务端难度策略、材料与上下文，返回问题、反馈和结构化证据；服务端校验后才提交。用户 JD 属于待分析材料，不能覆盖难度和允许动作。

模型调用不持有数据库事务。回答先检查本人归属与幂等回放，再领取租约；提交校验租约令牌、到期时间和预期轮次。迟到结果不能覆盖新状态；已提交回答不重复调用模型。检查点、问答和难度保存在原会话中。

## 简历与目标岗位

简历编辑保留基线、草稿、revision CAS、冲突比较和版本恢复。导入先返回解析结果，核对后才创建简历。AI 优化只生成建议，经逐项确认后写入本地草稿，再由正常保存产生版本。

`TargetJobRules` 保守拆分 JD 并定位简历原文，不计分、不推断录用概率。`TargetJobService` 负责归属与 revision；`TargetJobRepository` 管理私有岗位。

## 数据升级与退役

运行代码不再含 career、tracking、scoring 或独立 agent 模块。`langchain4j`、Jsoup、jsPDF、模板与打包中文字体已移除。历史 Flyway V1–V22 保留；V17 的旧岗位字段换算独立放在 migration 包，避免依赖已退役业务实现。V23 添加难度并为旧会话填标准值。

旧业务表不自动删除。账号身份删除时由外键级联清理旧表；V1 无外键的职业报告由账号仓储补充删除。历史面试 preparation 仅保留读取兼容。当前接口见 [HTTP 文档](接口文档.md)，明确退役清单见 [契约配置](../contracts/interview-focus-retirements.json)。
