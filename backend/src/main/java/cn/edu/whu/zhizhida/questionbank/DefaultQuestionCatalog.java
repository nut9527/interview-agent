package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.questionbank.domain.QuestionContentRules;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将题库适配为面试可用的固定版本快照，开始练习前校验发布状态、预期版本和题目内容。 */
@Service
public class DefaultQuestionCatalog implements QuestionCatalog {
    private final QuestionRepository repository;
    private final CurrentUserProvider users;
    private final ObjectProvider<QuestionSnapshotCache> caches;

    public DefaultQuestionCatalog(
            QuestionRepository repository,
            CurrentUserProvider users,
            ObjectProvider<QuestionSnapshotCache> caches) {
        this.repository = repository;
        this.users = users;
        this.caches = caches;
    }

    @Override
    public java.util.List<InterviewResources.Exercise> recommend(
            String competencyId, String query) {
        users.requireUserId();
        // 能力标识相同才视为明确匹配，不用无关题目补足列表。
        return repository.candidates(null, null, "").stream()
                .filter(q -> q.competencyId().equals(competencyId))
                .sorted(
                        java.util.Comparator
                                .<cn.edu.whu.zhizhida.questionbank.api.Question.Summary>
                                        comparingInt(
                                                q ->
                                                        overlap(
                                                                q.title()
                                                                        + String.join(
                                                                                " ", q.tags()),
                                                                query))
                                .reversed()
                                .thenComparing(q -> q.id()))
                .limit(3)
                .map(
                        q ->
                                new InterviewResources.Exercise(
                                        q.id(),
                                        q.version(),
                                        q.title(),
                                        q.difficulty(),
                                        "与本轮考察能力一致，可用于检验相关知识和实践。"))
                .toList();
    }

    private static int overlap(String text, String query) {
        int score = 0;
        for (int i = 0; i + 1 < query.length(); i++)
            if (text.contains(query.substring(i, i + 2))) score++;
        return score;
    }

    @Override
    public QuestionSnapshot requirePublished(String id, Integer expectedVersion) {
        users.requireUserId();
        return published(id, expectedVersion);
    }

    @Override
    public QuestionSnapshot requirePublishedForExecution(
            long owner, String id, Integer expectedVersion) {
        if (owner <= 0) throw new IllegalArgumentException("Invalid execution owner");
        return published(id, expectedVersion);
    }

    private QuestionSnapshot published(String id, Integer expectedVersion) {
        var current =
                repository
                        .state(id)
                        .filter(q -> q.published())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "题目不存在或已下架"));
        if (expectedVersion != null && expectedVersion != current.version())
            throw new BusinessException(ErrorCode.CONFLICT, "题目已修订，请刷新题目后开始练习");
        var cache = caches.getIfAvailable();
        java.util.function.Supplier<QuestionSnapshot> archive =
                () ->
                        repository
                                .snapshot(id, current.version())
                                .orElseThrow(() -> new IllegalStateException("题目版本归档缺失"));
        if (cache != null) return cache.read(id, current.version(), archive);
        var snapshot = archive.get();
        QuestionContentRules.validate(snapshot);
        return snapshot;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockPublishedForExecution(long owner, String id, Integer version) {
        if (owner <= 0) throw new IllegalArgumentException("Invalid execution owner");
        var state = repository.lockState(id);
        if (state.isEmpty()
                || !state.get().published()
                || (version != null && state.get().version() != version))
            throw new cn.edu.whu.zhizhida.common.SourceRevokedException();
    }
}
