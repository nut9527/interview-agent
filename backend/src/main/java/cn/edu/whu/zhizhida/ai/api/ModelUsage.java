package cn.edu.whu.zhizhida.ai.api;

/** 记录供应商报告的模型令牌数，不代表预占额度或计费金额；未知用量不等于零。 */
public record ModelUsage(Status status, Long inputTokens, Long outputTokens, Long totalTokens) {
    public enum Status { REPORTED, MISSING, INVALID }

    public ModelUsage {
        java.util.Objects.requireNonNull(status);
        if (status == Status.REPORTED) {
            if (inputTokens == null || outputTokens == null || totalTokens == null
                    || inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                    || totalTokens < inputTokens || totalTokens - inputTokens != outputTokens) {
                throw new IllegalArgumentException("Invalid reported model token usage");
            }
        } else if (inputTokens != null || outputTokens != null || totalTokens != null) {
            throw new IllegalArgumentException("Unknown usage must not contain token estimates");
        }
    }

    public static ModelUsage unknown(Status status) { return new ModelUsage(status, null, null, null); }
}
