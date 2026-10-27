package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.AiOptimizeRequest;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;

/** 适配简历优化的业务提示词，供应商协议由 JsonChatClient 处理。 */
public final class DefaultAiResumeService extends StructuredResumeOptimizer {
    private static final String SYSTEM_PROMPT = """
            你是“职智达”项目中的简历优化助手。
            只输出合法 JSON，不要输出 Markdown。
            JSON 格式必须为：
            {
              "optimizedText": "可直接放入简历的优化版中文经历",
              "suggestions": ["下一步建议1", "下一步建议2", "下一步建议3"],
              "improvements": [
                {
                  "category": "职责表达/成果量化/岗位匹配/结构可读性",
                  "issue": "原文存在的问题",
                  "action": "具体如何修改",
                  "example": "可参考的表达示例"
                }
              ],
              "targetKeywords": ["应自然覆盖的岗位关键词"],
              "optimizationSummary": "一句话说明本次优化策略"
            }
            要求：
            1. 不得编造经历、技术或数据；缺少量化数据时明确提示用户补充。
            2. 优化版使用“动作 + 技术/方法 + 结果”结构，并面向目标岗位。
            3. improvements 最多返回 20 项，必须对应原文中的具体问题；没有可靠问题时返回空数组。
            4. 建议应可执行，避免空泛评价。
            """;

    private final JsonChatClient chatClient;

    public DefaultAiResumeService(JsonChatClient chatClient, BusinessMetrics metrics) {
        super(metrics);
        this.chatClient = chatClient;
    }

    @Override
    protected JsonNode generate(AiOptimizeRequest request) {
        return chatClient.completeJson(SYSTEM_PROMPT,
                "目标岗位：" + request.targetRole() + "\n原始经历：" + request.rawText(), 0.3, 1800);
    }
}
