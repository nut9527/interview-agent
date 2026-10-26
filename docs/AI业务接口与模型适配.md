# AI 业务接口与模型适配

更新：2026-10-26。

| 业务 | 端口 | 实现与 Mock | 共用结构校验 |
| --- | --- | --- | --- |
| 简历表达优化 | `resume.api.AiResumeService` | `DefaultAiResumeService` / `MockAiResumeService` | `StructuredResumeOptimizer` |
| 模拟面试 | `interview.api.InterviewAiService` | `DefaultInterviewAiService` / `MockInterviewAiService` | `StructuredInterviewAiService` |

`AiProviderConfiguration` 在启动时解析 `AI_PROVIDER=mock/deepseek`。真实模型通过 `ai.api.JsonChatClient` 调用；通用供应商协议、JSON 解析、用量和有界并发由 ai 管理。Mock 和模型结果经过相同业务结构校验。

面试首问、评审及一次修正都携带服务端 `interviewDifficulty` 与 `difficultyGuidance`。JD、简历和回答放在独立材料字段，不能覆盖服务端策略。难度与会话持久化，续答始终读取原值。

独立求职准备 Agent、其工具调度/Worker/任务接口和 LangChain4j 协议适配已移除；岗位技能采集适配也已移除。无需 `AGENT_ENABLED` 等开关。模型调用的额度、超时、熔断及回答租约继续生效。
