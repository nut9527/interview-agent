package cn.edu.whu.zhizhida.common.execution;

import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 无持久状态的执行边界；尝试预占、回执和恢复由各运行时保存。 */
public final class AgentExecution {
    public static final long COMMIT_RESERVE_MILLIS = 5_000;
    private AgentExecution() {}

    public static void requireTime(long remainingMillis, long minimumMillis, String code) {
        if (remainingMillis < minimumMillis) throw new RunFailureException(code);
    }

    public static long modelTimeout(long remainingMillis, long reservedMillis, long callMillis) {
        long available = remainingMillis - reservedMillis;
        requireTime(available, 1_000, "RUN_BUDGET");
        return Math.min(callMillis, available);
    }

    public static JsonNode arguments(ObjectMapper json, JsonNode call) {
        var raw = call.path("function").path("arguments");
        if (!raw.isTextual()) throw new RunFailureException("TOOL_ARGUMENTS");
        JsonNode args;
        try {
            args = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw.asText());
        } catch (JacksonException ex) {
            throw new RunFailureException("TOOL_ARGUMENTS");
        }
        if (args == null || !args.isObject()) throw new RunFailureException("TOOL_ARGUMENTS");
        return args;
    }

    public static String callId(JsonNode call, Set<String> seen) {
        var id = call.path("id");
        if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 160 || !seen.add(id.asText()))
            throw new RunFailureException("TOOL_CALL_ID");
        return id.asText();
    }

    public static String modelFailureCode(Throwable failure) {
        String code = failure instanceof RunFailureException run ? run.code()
                : failure instanceof UpstreamServiceException upstream ? upstream.code() : null;
        return code != null && code.startsWith("MODEL_") ? code : "MODEL_UNAVAILABLE";
    }
}
