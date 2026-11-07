package cn.edu.whu.zhizhida.interview.execution;

/** 版本化的执行预算；观察步数与模型尝试次数分别计账。 */
public record AgentLoopPolicy(String version, int maxSteps, int maxModelAttempts, int maxToolBatches,
        int maxToolExecutions, int maxRepairs, long deadlineMillis, long callMillis) {
    public static final long COMMIT_RESERVE_MILLIS = 5_000;
    public static final long MIN_MODEL_BUDGET_MILLIS = 6_000;
    public static final long MIN_TOOL_BUDGET_MILLIS = 7_000;
    private static final long MIN_OBSERVATION_BUDGET_MILLIS = 12_000;

    public boolean explicit() { return !version.equals("B0"); }

    public String boundary(int steps, int attempts, int batches, int tools, long remainingMillis) {
        if (explicit() && steps >= maxSteps) return "STEP_LIMIT";
        // 最后一次模型尝试用于输出；不足 12 秒时停止取证，调用本身另预留 5 秒提交时间。
        if (attempts >= maxModelAttempts - 1 || batches >= maxToolBatches || tools >= maxToolExecutions
                || remainingMillis < MIN_OBSERVATION_BUDGET_MILLIS) return "BUDGET_LIMIT";
        return null;
    }
}
