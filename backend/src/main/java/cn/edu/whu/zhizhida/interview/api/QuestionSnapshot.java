package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 保存在面试检查点中的不可变学习参考，回答期间不重新加载。 */
public record QuestionSnapshot(String id, int version, String title, String question, String topic,
        String competencyId, InterviewDifficulty difficulty, List<String> tags, List<String> keyPoints,
        String referenceAnswer, List<String> commonMistakes, List<String> followUps,
        String sourceType, String sourceTitle, String sourceUrl) {
    public QuestionSnapshot withoutHints() {
        return new QuestionSnapshot(id,version,title,question,topic,competencyId,difficulty,tags,List.of(),"",List.of(),List.of(),sourceType,sourceTitle,sourceUrl);
    }
}
