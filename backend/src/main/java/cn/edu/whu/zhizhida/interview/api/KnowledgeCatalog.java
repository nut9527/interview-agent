package cn.edu.whu.zhizhida.interview.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 面试消费的资料端口：新快照按当前身份读取，后续检索使用已冻结原文，使用资格由调用方另行复核。 */
public interface KnowledgeCatalog {
    default String retrievalProfile() {
        return "lexical-v1";
    }

    default List<InterviewResources.Passage> recommend(String query) {
        return List.of();
    }

    /** 冻结 1～5 份不同的本人资料及版本；不存在、无权限或总正文超限时拒绝，topic 仅用于选择首批摘录。 */
    List<KnowledgeSnapshot> snapshot(List<String> ids, String topic);

    /** 文档间统一排序。调用方可先按排名截取片段，再按资料分组返回引用。 */
    List<RankedExcerpt> rank(List<KnowledgeSnapshot> snapshots, String question);

    /** 只返回回执内与本场冻结原文、版本和完整片段逐字段一致的引用。 */
    List<KnowledgeSnapshot.Reference> verifiedExcerpts(List<KnowledgeSnapshot> snapshots,
            List<KnowledgeSnapshot.Reference> receipts);

    record RankedExcerpt(KnowledgeSnapshot source, KnowledgeSnapshot.Excerpt excerpt) {
    }

    /** 从给定快照选取问题相关引用，保留原版本与哈希；不重新读取当前资料，也不重新授予访问权限。 */
    default List<KnowledgeSnapshot.Reference> select(List<KnowledgeSnapshot> snapshots, String question) {
        return references(rank(snapshots, question));
    }

    static List<KnowledgeSnapshot.Reference> references(List<RankedExcerpt> selected) {
        var groups = new LinkedHashMap<KnowledgeSnapshot, List<KnowledgeSnapshot.Excerpt>>();
        for (var match : selected) {
            groups.computeIfAbsent(match.source(), ignored -> new ArrayList<>()).add(match.excerpt());
        }
        return groups.entrySet().stream().map(entry -> {
            var source = entry.getKey();
            return new KnowledgeSnapshot.Reference(source.id(), source.title(), source.version(), source.sha256(),
                    entry.getValue());
        }).toList();
    }
}
