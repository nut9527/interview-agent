package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 已启用的资源上限；快照保持兼容，避免改变已受理任务的配置。 */
public record BudgetProfile(String version, AgentLoopPolicy execution, TokenLimits tokens,
        int finalRequests, long finalCallMillis) {
    public static final String VERSION = "budget-v1";

    // 保留旧快照中的 tokens 类型；所有受支持配置均序列化为 null。
    public record TokenLimits(int inputPerRequest, int outputPerRequest, long inputPerRun,
            long outputPerRun, int toolResult, int toolWindow) {
    }

    public static BudgetProfile production(String name) {
        return switch (name) {
            case "B0", "S1", "S3" -> new BudgetProfile(VERSION,
                    new AgentLoopPolicy(name, name.equals("S1") ? 1 : 3, 4, 2, 3, 1, 90_000, 25_000),
                    null, 2, 25_000);
            default -> throw new IllegalArgumentException("Agent budget profile is not enabled");
        };
    }

    /** 旧配置只读兼容；存在快照时必须与已知版本完全匹配。 */
    public static BudgetProfile frozen(ObjectMapper json, JsonNode config) {
        try {
            var profile = production(AgentLoopState.policy(config));
            // JSON 不区分 int 和 long，跨数据库序列化比较前统一两棵树。
            if (config.has("budgetProfile") && !json.readTree(config.path("budgetProfile").toString())
                    .equals(json.readTree(json.writeValueAsString(profile))))
                throw new IllegalArgumentException("Budget snapshot changed");
            return profile;
        } catch (IllegalArgumentException ex) {
            throw new RunFailureException("MODEL_CONFIG_CHANGED");
        }
    }

    public int reservedRequests(String phase, boolean repaired) {
        return switch (phase) {
            case "ACTION" -> finalRequests;
            case "FINAL" -> repaired ? 0 : finalRequests - 1;
            case "LEGACY" -> 0;
            default -> throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        };
    }

    public int attemptLimit(String phase, boolean repaired) {
        return execution.maxModelAttempts() - reservedRequests(phase, repaired);
    }

    /** 此超时不包含后续最终请求和事务提交的预留时间。 */
    public long callTimeout(String phase, boolean repaired, long remainingMillis) {
        long available = remainingMillis - AgentLoopPolicy.COMMIT_RESERVE_MILLIS
                - reservedRequests(phase, repaired) * finalCallMillis;
        long call = phase.equals("FINAL") ? finalCallMillis : execution.callMillis();
        if (available < 1_000)
            throw new RunFailureException("RUN_BUDGET");
        return Math.min(call, available);
    }

    /** 行动前须预留最低模型时间和两次最终调用；调用方另计提交时间。 */
    public long actionMinimumMillis() {
        return finalRequests * finalCallMillis + AgentLoopPolicy.MIN_MODEL_BUDGET_MILLIS;
    }

    /** 工具只能使用行动时间，不得占用最终生成、修正或提交预留。 */
    public long toolAvailableMillis(String phase, long remainingMillis) {
        if (remainingMillis <= 0)
            throw new RunFailureException("RUN_TOOL_BUDGET");
        // FIXED 检索可能先于首次 FINAL 调用，也必须预留两次最终调用。
        int reserved = switch (phase) {
            case "ACTION", "FINAL" -> finalRequests;
            case "LEGACY" -> 0;
            default -> throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        };
        long available = remainingMillis - AgentLoopPolicy.COMMIT_RESERVE_MILLIS
                - reserved * finalCallMillis;
        if (available <= 0)
            throw new RunFailureException("RUN_TOOL_BUDGET");
        return available;
    }

}
