package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 同一契约同时提供模型 Schema 和服务端边界校验。 */
record EvidenceToolArguments(String text, String number, int maxTextLength, int maxNumber) {
    JsonNode definition(ObjectMapper json, String name, String description) {
        var tool = json.createObjectNode().put("type", "function");
        var function = tool.putObject("function").put("name", name).put("description", description);
        var schema = function.putObject("parameters").put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties");
        properties.putObject(text).put("type", "string").put("minLength", 1).put("maxLength", maxTextLength);
        properties.putObject(number).put("type", "integer").put("minimum", 1).put("maximum", maxNumber);
        schema.putArray("required").add(text).add(number);
        return tool;
    }
    void validate(JsonNode args) {
        if (args == null || !args.isObject() || args.size() != 2
                || !args.path(text).isTextual() || args.path(text).asText().isBlank()
                || args.path(text).asText().length() > maxTextLength
                || !args.path(number).isIntegralNumber() || !args.path(number).canConvertToInt()
                || args.path(number).asInt() < 1 || args.path(number).asInt() > maxNumber)
            throw new RunFailureException("TOOL_ARGUMENTS");
    }
}
