package cn.edu.whu.zhizhida.questionbank.domain;

import cn.edu.whu.zhizhida.interview.api.QuestionSnapshot;
import cn.edu.whu.zhizhida.questionbank.api.QuestionTopic;
import java.util.List;

/** 题目内容的完整性规则；集成测试也使用这些规则校验所有归档版本和当前版本的种子题目。 */
public final class QuestionContentRules {
    private QuestionContentRules() {}
    public static void validate(QuestionSnapshot q) {
        require(q.id(), 64); require(q.title(), 160); require(q.question(), 4000);
        require(q.referenceAnswer(), 10000);
        if (q.version()<1 || q.difficulty()==null || !QuestionTopic.valueOf(q.topic()).competencyId().equals(q.competencyId()))
            throw new IllegalArgumentException("题目版本、难度或能力点映射无效");
        array(q.tags(), 1); array(q.keyPoints(), 3); array(q.commonMistakes(), 2); array(q.followUps(), 2);
        if (!List.of("ORIGINAL","EXTERNAL_REFERENCE").contains(q.sourceType())) throw new IllegalArgumentException("来源类型无效");
        if ("EXTERNAL_REFERENCE".equals(q.sourceType())) { require(q.sourceTitle(), 255); require(q.sourceUrl(), 2048); }
        if (q.sourceUrl()!=null && !q.sourceUrl().matches("https?://[^\\s]+")) throw new IllegalArgumentException("来源 URL 无效");
    }
    private static void array(List<String> values, int minimum) {
        if (values==null || values.size()<minimum || values.size()>30 || values.stream().distinct().count()!=values.size())
            throw new IllegalArgumentException("结构化题目数组不完整或重复");
        values.forEach(value -> require(value, 1000));
    }
    private static void require(String value, int max) {
        if (value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("题目字段缺失或过长");
    }
}
