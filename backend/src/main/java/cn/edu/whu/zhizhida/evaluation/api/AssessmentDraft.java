package cn.edu.whu.zhizhida.evaluation.api;

import java.util.List;

/** 未经信任的模型提案；来源身份、哈希和评价标准均由服务端指定。 */
public record AssessmentDraft(List<Item> items, DetailedAssessmentDraft detail) {
    public AssessmentDraft(List<Item> items) { this(items, null); }
    public record Item(AssessmentResult.Dimension dimension, AssessmentResult.Observation status,
            String claimText, AssessmentResult.ClaimStatus claimStatus, String limitation, List<Quote> quotes) { }
    public record Quote(@com.fasterxml.jackson.annotation.JsonProperty(required = true) int offsetStart,
            @com.fasterxml.jackson.annotation.JsonProperty(required = true) int offsetEnd, String quote) { }
}
