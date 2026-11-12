package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient.Response;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.InvalidModelOutputException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import java.util.HashSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 两种请求共用供应商协议与用量校验，保留原始助手消息供工具回执续接。 */
final class DeepSeekResponseReader {
    private DeepSeekResponseReader() {}

    static Response read(ObjectMapper json, BusinessMetrics metrics, String text) {
        JsonNode root;
        try {
            root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (JacksonException ex) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL", ex);
        }
        if (root == null || !root.isObject()) throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        var usage = DeepSeekUsage.parse(root.path("usage"));
        metrics.modelUsage(usage);
        var choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asText();
        switch (finish) {
            case "stop", "tool_calls" -> { }
            case "length" -> throw new InvalidModelOutputException(
                    InvalidModelOutputException.Reason.TRUNCATED, "MODEL_FINISH_LENGTH");
            case "content_filter" -> throw UpstreamServiceException.coded("MODEL_FINISH_CONTENT_FILTER");
            default -> throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
        var message = choice.path("message");
        if (!message.isObject() || !"assistant".equals(message.path("role").asText()))
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        if ("tool_calls".equals(finish)) {
            validateToolCalls(message.path("tool_calls"));
        } else {
            var calls = message.path("tool_calls");
            if (!message.path("content").isTextual()
                    || !calls.isMissingNode() && !calls.isNull() && (!calls.isArray() || !calls.isEmpty()))
                throw UpstreamServiceException.coded("MODEL_PROTOCOL");
            if (message.path("content").asText().isBlank())
                throw new InvalidModelOutputException(InvalidModelOutputException.Reason.EMPTY, "MODEL_PROTOCOL");
        }
        return new Response(message, finish, usage);
    }

    private static void validateToolCalls(JsonNode calls) {
        if (!calls.isArray() || calls.isEmpty()) throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        var ids = new HashSet<String>();
        for (var call : calls) {
            var function = call.path("function");
            if (!"function".equals(call.path("type").asText()) || !call.path("id").isTextual()
                    || call.path("id").asText().isBlank() || !ids.add(call.path("id").asText())
                    || !function.path("arguments").isTextual() || !function.path("name").isTextual()
                    || function.path("name").asText().isBlank())
                throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
    }
}
