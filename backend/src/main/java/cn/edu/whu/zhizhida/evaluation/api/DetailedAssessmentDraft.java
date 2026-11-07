package cn.edu.whu.zhizhida.evaluation.api;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 仅表示模型提案；来源键只能选择服务端已固定的来源，不能指定网址或账号。 */
public record DetailedAssessmentDraft(String protocolVersion, List<Item> items) {
    public enum Relation { SELF_REPORT, SUPPORT, COUNTEREVIDENCE }
    public record Item(DetailedAssessment.Dimension dimension, AssessmentResult.Observation status,
            String claimText, AssessmentResult.ClaimStatus claimStatus, String limitation,
            boolean majorCorrection, List<Citation> citations) { }
    public record Citation(String sourceKey, @JsonProperty(required = true) int offsetStart,
            @JsonProperty(required = true) int offsetEnd, String quote, Relation relation) { }
}
