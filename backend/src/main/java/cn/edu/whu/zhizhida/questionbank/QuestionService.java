package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory;
import cn.edu.whu.zhizhida.questionbank.api.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/** 提供已发布题目的浏览与抽题，并通过面试模块的历史接口获取当前用户的练习进度。 */
@Service
public class QuestionService {
    private final QuestionRepository repository;
    private final CurrentUserProvider users;
    private final QuestionPracticeHistory history;

    public QuestionService(
            QuestionRepository repository,
            CurrentUserProvider users,
            QuestionPracticeHistory history) {
        this.repository = repository;
        this.users = users;
        this.history = history;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Question.Summary> page(
            PageRequest page, QuestionTopic topic, InterviewDifficulty difficulty, String keyword) {
        users.requireUserId();
        if (keyword != null && keyword.length() > 100)
            throw new UserInputException("关键词不能超过 100 字符");
        return repository.page(page, topic, difficulty, keyword == null ? "" : keyword.strip());
    }

    public Question find(String id) {
        users.requireUserId();
        return repository
                .find(id)
                .filter(Question::published)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "题目不存在或已下架"));
    }

    public Question.Metadata metadata() {
        users.requireUserId();
        return new Question.Metadata(
                Arrays.stream(QuestionTopic.values())
                        .map(t -> new Question.TopicOption(t, t.label(), t.competencyId()))
                        .toList(),
                Arrays.stream(InterviewDifficulty.values())
                        .map(d -> new Question.DifficultyOption(d, d.label()))
                        .toList());
    }

    public java.util.List<cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory.Progress>
            progress() {
        users.requireUserId();
        return history.progress();
    }

    public PageResult<cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory.Practice> history(
            String id, PageRequest page) {
        users.requireUserId();
        return history.history(id, page);
    }

    public Question.Draw draw(
            QuestionTopic topic,
            InterviewDifficulty difficulty,
            String keyword,
            int count,
            int rounds) {
        users.requireUserId();
        if (count < 1 || count > 10 || rounds < 3 || rounds > 6)
            throw new UserInputException("每组 1 至 10 题，每题 3 至 6 轮");
        if (keyword != null && keyword.length() > 100)
            throw new UserInputException("关键词不能超过 100 字符");
        var priorities = new java.util.HashMap<String, Integer>();
        history.progress()
                .forEach(
                        p ->
                                priorities.put(
                                        p.questionId(),
                                        p.status()
                                                        == cn.edu.whu.zhizhida.interview.api
                                                                .QuestionPracticeHistory.Status
                                                                .NEEDS_REVIEW
                                                ? 0
                                                : 2));
        var candidates =
                new java.util.ArrayList<>(
                        repository.candidates(
                                topic, difficulty, keyword == null ? "" : keyword.strip()));
        // 先打乱同优先级题目的顺序，再稳定排序：待复习优先，其次未练习，最后其他已练习题目。
        java.util.Collections.shuffle(candidates);
        candidates.sort(java.util.Comparator.comparingInt(q -> priorities.getOrDefault(q.id(), 1)));
        return new Question.Draw(candidates.stream().limit(count).toList(), count, rounds);
    }
}
