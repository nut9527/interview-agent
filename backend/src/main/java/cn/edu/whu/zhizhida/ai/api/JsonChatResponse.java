package cn.edu.whu.zhizhida.ai.api;

import tools.jackson.databind.JsonNode;

/** 供应商协议与 JSON 语法已通过校验，业务字段仍需按所属业务约定验证。 */
public record JsonChatResponse(JsonNode payload, ModelUsage usage) { }
