package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord.Source;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Selection;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.SelfTest;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;

/** 根据用户指定的资料版本和片段生成自测题，保存为待确认记录供用户核对。 */
@Service
public class KnowledgeLearningService {
    private final KnowledgeService documents;
    private final KnowledgeQuizGenerator generator;
    private final KnowledgeLearningRecords records;
    private final AiRequestQuota quota;
    private final CurrentUserProvider users;

    public KnowledgeLearningService(KnowledgeService documents, KnowledgeQuizGenerator generator,
            KnowledgeLearningRecords records, AiRequestQuota quota, CurrentUserProvider users) {
        this.documents = documents;
        this.generator = generator;
        this.records = records;
        this.quota = quota;
        this.users = users;
    }

    public List<SelfTest> generate(Generate request) {
        if (request.sources().stream().map(Selection::documentId).distinct().count() != request.sources().size())
            throw new UserInputException("每份资料只能选择一次");
        // 先固定资料版本再选择片段，避免出题来源随资料后续编辑而变化。
        var snapshots = request.sources().stream().map(s -> documents.version(s.documentId(), s.version())).toList();
        var hits = QuizSourceSelector.select(snapshots, request,
                (doc, query, limit) -> documents.searchVersions(query, List.of(doc), limit));
        if (hits.isEmpty())
            return List.of();
        quota.admit(users.requireUserId().toString(), 2);
        var generated = generator.generate(hits, request);
        var sources = snapshots.stream().map(d -> new Source(d.id(), d.title(), d.sourceName(), d.version(), true))
                .toList();
        return records.store(generated.questions().stream()
                .map(q -> new SelfTest(UUID.randomUUID().toString(), q, false, generated.executionMode(), sources))
                .toList());
    }
}
