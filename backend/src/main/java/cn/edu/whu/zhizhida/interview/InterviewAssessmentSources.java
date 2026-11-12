package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentReference;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import java.util.*;

/** 提示词与校验共用同一来源目录；只引用已归档题目和资料摘录，不读取实时文档。 */
public final class InterviewAssessmentSources {
    private InterviewAssessmentSources() { }
    static List<AssessmentReference> references(long owner, InterviewTraining training) {
        return references(owner, training, List.of());
    }

    public static List<AssessmentReference> references(long owner, InterviewTraining training,
            List<cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Reference> verified) {
        if (training == null) return List.of();
        var result = new ArrayList<AssessmentReference>();
        if (training.question() != null) {
            var q = training.question();
            add(result, owner, "question/" + q.id(), "QUESTION_REFERENCE", q.id(), Integer.toString(q.version()), q.referenceAnswer());
        }
        if (training.knowledge() != null) for (var doc : training.knowledge()) {
            if (doc.content() == null || !Sha256.hex(doc.content()).equals(doc.sha256()))
                throw new IllegalArgumentException("本场知识快照版本哈希不一致");
            for (var excerpt : doc.excerpts()) {
                if (!doc.content().contains(excerpt.content())) throw new IllegalArgumentException("资料片段与本场版本不一致");
                String key = "knowledge/" + doc.id() + "/" + excerpt.chunkId();
                add(result, owner, key, "KNOWLEDGE_EXCERPT", key, doc.version() + "@" + doc.sha256(), excerpt.content());
            }
        }
        // 先保留初始目录。只有工具端口从冻结快照复核过的新增完整片段可以占用剩余名额。
        for (var doc : verified) for (var excerpt : doc.excerpts()) {
            String key = "knowledge/" + doc.id() + "/" + excerpt.chunkId();
            if (result.stream().noneMatch(existing -> existing.key().equals(key)))
                add(result, owner, key, "KNOWLEDGE_EXCERPT", key, doc.version() + "@" + doc.sha256(), excerpt.content());
        }
        return List.copyOf(result);
    }
    private static void add(List<AssessmentReference> out, long owner, String key, String type, String id, String version, String text) {
        // 不得静默截断来源；未纳入目录的引用不能作为技术判断的依据。
        if (text == null || text.isBlank() || text.length() > 2000 || out.size() == 6
                || out.stream().mapToInt(r -> r.text().length()).sum() + text.length() > 6000) return;
        if (out.stream().anyMatch(r -> r.key().equals(key))) throw new IllegalArgumentException("资料来源键重复");
        out.add(new AssessmentReference(key, owner, type, id, version, text, Sha256.hex(text),
                AssessmentResult.Normalization.RAW_UTF16, AssessmentResult.Validity.VALID));
    }
}
