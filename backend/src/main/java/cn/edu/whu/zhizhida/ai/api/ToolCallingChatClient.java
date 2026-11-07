package cn.edu.whu.zhizhida.ai.api;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;

/** 每次只发起一次模型调用；循环推进、重试和工具执行均由应用层控制。 */
public interface ToolCallingChatClient {
    Response complete(Request request);
    /** 冻结本次运行使用的模型配置和协议版本，防止执行途中切换配置。 */
    record Config(String model, String thinking, String protocolVersion) { }
    record Request(Config config, List<JsonNode> messages, List<JsonNode> tools,
                   boolean toolsAllowed, Duration timeout, String attemptId) { }
    /** 保留完整助手消息，供后续携带工具回执继续对话；业务内容仍需调用方校验。 */
    record Response(JsonNode message, String finishReason, ModelUsage usage) { }
    Config configuration();
    /** 不含凭证或用户输入的实际传输配置；参与持久运行的配置一致性检查。 */
    default java.util.Map<String, String> executionProfile() {
        return java.util.Map.of("adapter", getClass().getName());
    }
}
