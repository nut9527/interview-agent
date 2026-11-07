package cn.edu.whu.zhizhida.interview.execution;

import java.util.Set;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 读取已有未版本化检查点，缺失或格式错误的字段不做强制转换。 */
final class InterviewRunCheckpoint {
    private static final Set<String> STAGES = Set.of("FIXED", "KNOWLEDGE_PREFETCH", "MODEL", "TOOL", "VALIDATE", "ACTION_VALIDATE");
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");
    private static final Set<String> STOPS = Set.of("NO_PROGRESS", "STEP_LIMIT", "BUDGET_LIMIT");

    enum Reason { INVALID_JSON, MISSING_FIELD, INVALID_TYPE, INVALID_VALUE, INCONSISTENT_STATE }

    /** 仅保留固定原因和路径元数据，不保留检查点正文或解析异常消息。 */
    static final class Invalid extends IllegalStateException {
        private final Reason reason;
        private final String path;

        private Invalid(Reason reason, String path) {
            super("RUN_CHECKPOINT_INVALID");
            this.reason = reason;
            this.path = path;
        }

        Reason reason() { return reason; }
        String path() { return path; }
    }

    private InterviewRunCheckpoint() { }

    static ObjectNode read(ObjectMapper json, String saved, String route) {
        if (saved == null) return json.createObjectNode();
        JsonNode value;
        try {
            value = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(saved);
        } catch (RuntimeException ex) {
            throw invalid(Reason.INVALID_JSON, "$");
        }
        if (value == null || !value.isObject()) throw invalid(Reason.INVALID_TYPE, "$");
        var checkpoint = (ObjectNode) value;
        validate(checkpoint, route);
        return checkpoint;
    }

    static void validate(ObjectNode cp, String route) {
        var stage = field(cp, "stage", "stage");
        if (!stage.isTextual()) throw invalid(Reason.INVALID_TYPE, "stage");
        if (!STAGES.contains(stage.asText())) throw invalid(Reason.INVALID_VALUE, "stage");
        bool(cp, "repaired", true);
        array(cp, "sources", true, false);
        array(cp, "memoryUses", false, false);
        if (cp.has("practiceMemory") && !cp.path("practiceMemory").isObject())
            throw invalid(Reason.INVALID_TYPE, "practiceMemory");
        bool(cp, "fixedReserved", false);
        bool(cp, "batchReserved", false);
        integer(cp, "toolIndex", false, "toolIndex");
        messages(cp);
        loop(cp);
        if (cp.has("outputPhase")) {
            if (!Set.of("ACTION", "FINAL").contains(cp.path("outputPhase").asText()))
                throw invalid(Reason.INVALID_VALUE, "outputPhase");
            bool(cp, "actionRepaired", true);
            if (stage.asText().equals("ACTION_VALIDATE") && !cp.path("outputPhase").asText().equals("ACTION")
                    || stage.asText().equals("VALIDATE") && !cp.path("outputPhase").asText().equals("FINAL")
                    || stage.asText().equals("TOOL") && (!cp.path("outputPhase").asText().equals("ACTION")
                        || cp.path("actionRepaired").asBoolean()))
                throw invalid(Reason.INCONSISTENT_STATE, "outputPhase");
        } else if (stage.asText().equals("ACTION_VALIDATE")) throw invalid(Reason.MISSING_FIELD, "outputPhase");
        switch (stage.asText()) {
            case "FIXED" -> {
                if (!"FIXED".equals(route) || cp.path("repaired").asBoolean())
                    throw invalid(Reason.INCONSISTENT_STATE, "stage");
            }
            case "KNOWLEDGE_PREFETCH" -> {
                if (!"TOOL".equals(route) || cp.path("repaired").asBoolean() || cp.has("response")
                        || !cp.path("sources").isEmpty() || cp.path("actionRepaired").asBoolean()
                        || (cp.has("outputPhase") && !"ACTION".equals(cp.path("outputPhase").asText())))
                    throw invalid(Reason.INCONSISTENT_STATE, "stage");
            }
            case "TOOL" -> toolStage(cp, route);
            case "VALIDATE", "ACTION_VALIDATE" -> {
                var response = response(cp);
                var content = field(response, "content", "response.content");
                if (!content.isTextual()) throw invalid(Reason.INVALID_TYPE, "response.content");
                if (content.asText().isBlank()) throw invalid(Reason.INVALID_VALUE, "response.content");
                if (response.hasNonNull("tool_calls") && (!response.path("tool_calls").isArray()
                        || !response.path("tool_calls").isEmpty()))
                    throw invalid(Reason.INCONSISTENT_STATE, "response.tool_calls");
                var messages = cp.path("messages");
                if (!messages.get(messages.size() - 1).equals(response))
                    throw invalid(Reason.INCONSISTENT_STATE, "messages");
            }
            default -> { /* MODEL 可保留上一响应及已完成的工具批次，不因此判定检查点冲突。 */ }
        }
    }

    private static void toolStage(ObjectNode cp, String route) {
        if (!"TOOL".equals(route) || cp.path("repaired").asBoolean())
            throw invalid(Reason.INCONSISTENT_STATE, "stage");
        var response = response(cp);
        var calls = field(response, "tool_calls", "response.tool_calls");
        if (!calls.isArray()) throw invalid(Reason.INVALID_TYPE, "response.tool_calls");
        if (calls.isEmpty()) throw invalid(Reason.INVALID_VALUE, "response.tool_calls");
        integer(cp, "toolIndex", true, "toolIndex");
        bool(cp, "batchReserved", true);
        int index = cp.path("toolIndex").asInt();
        if (index > calls.size()) throw invalid(Reason.INVALID_VALUE, "toolIndex");
        if (index > 0 && !cp.path("batchReserved").asBoolean())
            throw invalid(Reason.INCONSISTENT_STATE, "batchReserved");
        var ids = new java.util.HashSet<String>();
        for (var call : calls) {
            if (!call.isObject() || !call.path("id").isTextual() || call.path("id").asText().isBlank()
                    || call.path("id").asText().length() > 160 || !ids.add(call.path("id").asText()))
                throw invalid(Reason.INVALID_VALUE, "response.tool_calls.id");
            if (!call.path("function").isObject() || !call.path("function").path("name").isTextual()
                    || call.path("function").path("name").asText().isBlank()
                    || !call.path("function").path("arguments").isTextual())
                throw invalid(Reason.INVALID_TYPE, "response.tool_calls.function");
        }
        // 每个持久索引必须对应顺序一致的工具消息，不静默重放或跳过。
        var messages = cp.path("messages");
        int responseIndex = messages.size() - index - 1;
        if (responseIndex < 0 || !messages.get(responseIndex).equals(response))
            throw invalid(Reason.INCONSISTENT_STATE, "messages");
        for (int i = 0; i < index; i++) {
            var receipt = messages.get(responseIndex + i + 1);
            if (!receipt.path("role").asText().equals("tool")
                    || !receipt.path("tool_call_id").equals(calls.get(i).path("id")))
                throw invalid(Reason.INCONSISTENT_STATE, "messages.tool_call_id");
        }
    }

    private static JsonNode response(ObjectNode cp) {
        var response = field(cp, "response", "response");
        if (!response.isObject()) throw invalid(Reason.INVALID_TYPE, "response");
        if (!response.path("role").asText().equals("assistant"))
            throw invalid(Reason.INVALID_VALUE, "response.role");
        return response;
    }

    private static void messages(ObjectNode cp) {
        var messages = field(cp, "messages", "messages");
        if (!messages.isArray()) throw invalid(Reason.INVALID_TYPE, "messages");
        if (messages.isEmpty()) throw invalid(Reason.INVALID_VALUE, "messages");
        for (var message : messages) {
            if (!message.isObject()) throw invalid(Reason.INVALID_TYPE, "messages");
            String role = message.path("role").asText();
            if (!ROLES.contains(role)) throw invalid(Reason.INVALID_VALUE, "messages.role");
            boolean toolRequest = role.equals("assistant") && message.path("tool_calls").isArray()
                    && !message.path("tool_calls").isEmpty();
            if (!message.path("content").isTextual()
                    && !(toolRequest && (!message.has("content") || message.path("content").isNull())))
                throw invalid(Reason.INVALID_TYPE, "messages.content");
            if (role.equals("tool") && (!message.path("tool_call_id").isTextual()
                    || message.path("tool_call_id").asText().isBlank()))
                throw invalid(Reason.INVALID_TYPE, "messages.tool_call_id");
        }
    }

    private static void loop(ObjectNode cp) {
        if (!cp.has("loop")) return;
        if (!cp.path("loop").isObject()) throw invalid(Reason.INVALID_TYPE, "loop");
        var loop = cp.path("loop");
        integer(loop, "steps", false, "loop.steps");
        integer(loop, "noChange", false, "loop.noChange");
        for (String name : Set.of("evidence", "actions")) {
            if (!loop.has(name)) continue;
            if (!loop.path(name).isArray()) throw invalid(Reason.INVALID_TYPE, "loop." + name);
            for (var value : loop.path(name))
                if (!value.isTextual()) throw invalid(Reason.INVALID_TYPE, "loop." + name);
        }
        for (String name : Set.of("closed", "lastSearchEmpty"))
            if (loop.has(name) && !loop.path(name).isBoolean())
                throw invalid(Reason.INVALID_TYPE, "loop." + name);
        if (loop.has("stopReason") && (!loop.path("stopReason").isTextual()
                || !STOPS.contains(loop.path("stopReason").asText())))
            throw invalid(Reason.INVALID_VALUE, "loop.stopReason");
        if (loop.path("closed").asBoolean() && !loop.has("stopReason"))
            throw invalid(Reason.INCONSISTENT_STATE, "loop.closed");
    }

    private static void array(JsonNode node, String name, boolean required, boolean strings) {
        if (!required && !node.has(name)) return;
        var array = field(node, name, name);
        if (!array.isArray()) throw invalid(Reason.INVALID_TYPE, name);
        for (var value : array)
            if (strings ? !value.isTextual() : !value.isObject()) throw invalid(Reason.INVALID_TYPE, name);
    }

    private static void bool(JsonNode node, String name, boolean required) {
        if (!required && !node.has(name)) return;
        if (!field(node, name, name).isBoolean()) throw invalid(Reason.INVALID_TYPE, name);
    }

    private static void integer(JsonNode node, String name, boolean required, String path) {
        if (!required && !node.has(name)) return;
        var value = field(node, name, path);
        if (!value.isIntegralNumber()) throw invalid(Reason.INVALID_TYPE, path);
        if (!value.canConvertToInt() || value.asInt() < 0) throw invalid(Reason.INVALID_VALUE, path);
    }

    private static JsonNode field(JsonNode node, String name, String path) {
        if (!node.has(name)) throw invalid(Reason.MISSING_FIELD, path);
        return node.path(name);
    }

    private static Invalid invalid(Reason reason, String path) { return new Invalid(reason, path); }
}
