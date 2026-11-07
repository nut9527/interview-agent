package cn.edu.whu.zhizhida.interview.execution;

import java.util.Objects;
import java.util.Set;

/** 应用重试策略有版本约束，每次重试都消耗新的持久尝试。 */
public final class ModelRetryPolicy {
    public static final String VERSION = "durable-backoff-v1";
    private static final Set<String> RETRYABLE = Set.of("MODEL_NETWORK", "MODEL_HTTP_429",
            "MODEL_HTTP_500", "MODEL_HTTP_502", "MODEL_HTTP_503", "MODEL_HTTP_504");

    private ModelRetryPolicy() { }

    public static boolean retryable(String code) { return RETRYABLE.contains(code); }

    /** 按 1/2/4/8 秒加 Run 固定抖动退避，恢复不重新计算起点。 */
    public static long delayMillis(String runId, int failedOrdinal) {
        if (failedOrdinal < 1) throw new IllegalArgumentException("Invalid attempt ordinal");
        long base = 1_000L << Math.min(failedOrdinal - 1, 3);
        return base + Math.floorMod(Objects.hash(runId, failedOrdinal), 251);
    }
}
