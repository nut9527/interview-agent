package cn.edu.whu.zhizhida.interview.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 出站投影保持确定性，恢复仍以检查点和回执为准。
 * 限制按序列化后的 UTF-16 单元计数，不代表 token 或计费输入估算。
 */
public final class RunContextAssembler {
    public static final String VERSION = "context-window-v1";
    public static final int MAX_INPUT_CHARS = 150_000;
    public static final int MAX_TOOL_CHARS = 24_000;
    private static final String NOTICE = "\nCONTEXT_OMITTED 表示服务端省略了之前已提供的工具正文，不表示资料不存在。"
            + "保留的引文仍是原文；不得根据摘要标识推断遗漏正文。需要重读时可调用 reread 中的工具和参数，"
            + "仍受原权限、来源资格和工具预算约束；工具关闭时只能根据已获得证据保留未知。";
    private final ObjectMapper json;

    public RunContextAssembler(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    public static Map<String, Object> manifest() {
        return Map.of("version", VERSION, "measurement", "SERIALIZED_UTF16_NOT_TOKENS",
                "maxInputChars", MAX_INPUT_CHARS, "maxToolChars", MAX_TOOL_CHARS,
                "selection", "NEWEST_COMPLETE_BATCH_THEN_RECENT_WHOLE_RESULTS", "notice", NOTICE);
    }

    public record Prepared(List<JsonNode> messages, List<JsonNode> tools, boolean toolsAllowed,
            String requestHash, int inputChars, int toolChars, int omittedResults) {
    }

    private record Result(int index, int batch, JsonNode function, JsonNode message, String fingerprint) {
    }

    public Prepared assemble(String configuration, List<JsonNode> messages, List<JsonNode> tools, boolean allowed) {
        return assemble(configuration, messages, tools, allowed, MAX_INPUT_CHARS, MAX_TOOL_CHARS);
    }

    // 小预算只用于确定性边界夹具，不作为运行时配置。
    Prepared assemble(String configuration, List<JsonNode> messages, List<JsonNode> tools, boolean allowed,
            int inputLimit, int toolLimit) {
        var projected = new ArrayList<JsonNode>();
        for (var message : messages)
            projected.add(message.deepCopy());
        List<JsonNode> definitions = tools.stream().map(JsonNode::deepCopy).toList();
        var results = results(projected);
        int latestBatch = results.isEmpty() ? -1 : results.getLast().batch();
        var omitted = new HashSet<Integer>();
        var seen = new HashSet<String>();
        // 仅替换较早批次，每个新结果必须完整传给下一次模型调用。
        for (var result : results.reversed()) {
            boolean duplicate = !seen.add(result.fingerprint());
            if (result.batch() != latestBatch && duplicate)
                omit(projected, result, omitted);
        }
        int toolChars = toolChars(projected);
        int noticeChars = json.writeValueAsString(NOTICE).length() - 2; // 计入 JSON 转义后的长度，扣除字符串两侧引号。
        for (var result : results) {
            if (toolChars <= toolLimit && envelope(projected, definitions, allowed).toString().length()
                    + (omitted.isEmpty() ? 0 : noticeChars) <= inputLimit)
                break;
            if (result.batch() != latestBatch && !omitted.contains(result.index())) {
                omit(projected, result, omitted);
                toolChars = toolChars(projected);
            }
        }
        if (!omitted.isEmpty()) {
            // 不编辑供应商私有 assistant 字段，包括推理和签名元数据。
            if (projected.isEmpty() || !projected.getFirst().path("role").asString().equals("system"))
                throw new RunFailureException("RUN_CONTEXT_PROTOCOL");
            var system = (ObjectNode) projected.getFirst();
            system.put("content", system.path("content").asString() + NOTICE);
        }
        String serialized = envelope(projected, definitions, allowed).toString();
        if (toolChars > toolLimit || serialized.length() > inputLimit)
            throw new RunFailureException("RUN_CONTEXT_BUDGET");
        return new Prepared(List.copyOf(projected), definitions, allowed,
                InterviewRunStore.sha(configuration + "\n" + serialized), serialized.length(), toolChars,
                omitted.size());
    }

    private ObjectNode envelope(List<JsonNode> messages, List<JsonNode> tools, boolean allowed) {
        var value = json.createObjectNode().put("toolsAllowed", allowed);
        value.set("messages", json.valueToTree(messages));
        value.set("tools", json.valueToTree(tools));
        return value;
    }

    /** 准入前校验工具与结果配对，恢复的 MODEL 检查点也不例外。 */
    private List<Result> results(List<JsonNode> messages) {
        var results = new ArrayList<Result>();
        var ids = new HashSet<String>();
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.path("role").asString().equals("tool"))
                throw new RunFailureException("RUN_CONTEXT_PROTOCOL");
            var calls = message.path("tool_calls");
            if (!message.path("role").asString().equals("assistant") || !calls.isArray() || calls.isEmpty())
                continue;
            int batch = i;
            for (var call : calls) {
                String id = call.path("id").asString();
                var function = call.path("function");
                if (id.isBlank() || !ids.add(id) || !function.path("name").isString()
                        || !function.path("arguments").isString() || ++i >= messages.size())
                    throw new RunFailureException("RUN_CONTEXT_PROTOCOL");
                var receipt = messages.get(i);
                if (!receipt.path("role").asString().equals("tool")
                        || !receipt.path("tool_call_id").asString().equals(id) || !receipt.path("content").isString())
                    throw new RunFailureException("RUN_CONTEXT_PROTOCOL");
                results.add(new Result(i, batch, function, receipt,
                        InterviewRunStore.sha(function.toString() + "\n" + receipt.path("content").asString())));
            }
        }
        return results;
    }

    private void omit(List<JsonNode> messages, Result result, Set<Integer> omitted) {
        var pointer = json.createObjectNode().put("status", "CONTEXT_OMITTED").put("truncated", true)
                .put("receiptSha256", InterviewRunStore.sha(result.message().path("content").asString()));
        // 仅回读原始调用，不使用模型选择的 URL，也不新增高权限回执接口。
        pointer.set("reread", result.function().deepCopy());
        // 来源标识在正文移出窗口后仍保留，只作定位符，不替代引文。
        try {
            var original = json.readTree(result.message().path("content").asString());
            if (original != null && original.isObject()) {
                for (String field : List.of("status", "sourceVersion", "sha256", "version"))
                    if (original.has(field))
                        pointer.set(field.equals("status") ? "originalStatus" : field, original.path(field));
                if (original.path("references").isArray()) {
                    var references = pointer.putArray("sourceLocators");
                    for (var reference : original.path("references")) {
                        var locator = references.addObject();
                        for (String field : List.of("id", "version", "sha256"))
                            if (reference.has(field))
                                locator.set(field, reference.path(field));
                        var chunks = locator.putArray("chunkIds");
                        for (var excerpt : reference.path("excerpts"))
                            if (excerpt.has("chunkId"))
                                chunks.add(excerpt.path("chunkId"));
                    }
                }
            }
        } catch (tools.jackson.core.JacksonException ignored) {
            /* 非 JSON 工具正文仍通过回执哈希定位，不强行解释其结构。 */ }
        var replacement = result.message().deepCopy();
        ((ObjectNode) replacement).put("content", pointer.toString());
        // 较小结果不应因改用指针而变大。
        if (pointer.toString().length() >= result.message().path("content").asString().length())
            return;
        messages.set(result.index(), replacement);
        omitted.add(result.index());
    }

    private int toolChars(List<JsonNode> messages) {
        long count = 0;
        for (var message : messages)
            if (message.path("role").asString().equals("tool"))
                count += message.path("content").asString().length();
        if (count > Integer.MAX_VALUE)
            throw new RunFailureException("RUN_CONTEXT_BUDGET");
        return (int) count;
    }
}
