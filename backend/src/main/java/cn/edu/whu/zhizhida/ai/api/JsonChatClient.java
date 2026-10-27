package cn.edu.whu.zhizhida.ai.api;

import tools.jackson.databind.JsonNode;

/**
 * 供应商无关的结构化对话接口，返回 JSON 载荷与可获得的模型用量。
 * 调用方需继续校验业务字段，得到 JSON 对象本身不代表业务成功。
 */
public interface JsonChatClient {
    JsonChatResponse complete(String systemPrompt, String userPrompt, double temperature, int maxTokens);

    /** 返回结构化对象仅表示协议解析成功，业务字段仍需由调用方校验。 */
    default JsonNode completeJson(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return complete(systemPrompt, userPrompt, temperature, maxTokens).payload();
    }
}
