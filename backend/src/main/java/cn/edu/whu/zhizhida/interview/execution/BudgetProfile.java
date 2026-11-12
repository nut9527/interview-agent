package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.common.execution.AgentExecution;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 受理任务时固定的资源上限和最终输出预留。 */
public record BudgetProfile(String version, AgentLoopPolicy execution,
        int finalRequests, long finalCallMillis) {
    public static final String VERSION = "budget-v1";

    public static BudgetProfile production(String name) {
        return switch (name) {
            case "A1" -> new BudgetProfile(VERSION,
                    new AgentLoopPolicy(name, 4, 6, 3, 4, 1, 120_000, 25_000), 2, 25_000);
            case "B0", "S1", "S3" -> new BudgetProfile(VERSION,
                    new AgentLoopPolicy(name, name.equals("S1") ? 1 : 3, 4, 2, 3, 1, 90_000, 25_000),
                    2, 25_000);
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
            default -> throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        };
    }

    public int attemptLimit(String phase, boolean repaired) {
        return execution.maxModelAttempts() - reservedRequests(phase, repaired);
    }

    /** 此超时不包含后续最终请求和事务提交的预留时间。 */
    public long callTimeout(String phase, boolean repaired, long remainingMillis) {
        long call = phase.equals("FINAL") ? finalCallMillis : execution.callMillis();
        return AgentExecution.modelTimeout(remainingMillis, AgentLoopPolicy.COMMIT_RESERVE_MILLIS
                + reservedRequests(phase, repaired) * finalCallMillis, call);
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
            default -> throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        };
        long available = remainingMillis - AgentLoopPolicy.COMMIT_RESERVE_MILLIS
                - reserved * finalCallMillis;
        if (available <= 0)
            throw new RunFailureException("RUN_TOOL_BUDGET");
        return available;
    }

}
