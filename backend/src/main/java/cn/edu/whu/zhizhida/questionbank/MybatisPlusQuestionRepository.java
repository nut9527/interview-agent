package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.questionbank.api.*;
import cn.edu.whu.zhizhida.questionbank.persistence.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisPlusQuestionRepository implements QuestionRepository {
    private final QuestionMapper mapper;
    private final JsonColumnCodec json;
    private final QuestionVersionMapper versions;
    public MybatisPlusQuestionRepository(QuestionMapper mapper, JsonColumnCodec json, QuestionVersionMapper versions) {
        this.mapper = mapper; this.json = json; this.versions = versions;
    }
    public Optional<cn.edu.whu.zhizhida.interview.api.QuestionSnapshot> snapshot(String id, int version) {
        return Optional.ofNullable(versions.selectOne(new QueryWrapper<QuestionVersionEntity>()
                .eq("question_id", id).eq("version", version))).map(e ->
                json.read(e.getContentJson(), cn.edu.whu.zhizhida.interview.api.QuestionSnapshot.class));
    }
    public PageResult<Question.Summary> page(PageRequest page, QuestionTopic topic, InterviewDifficulty difficulty, String keyword) {
        var query = filter(topic, difficulty, keyword);
        long total = mapper.selectCount(query);
        var rows = summaries(query.orderByAsc("id").last(page.limitSql()));
        return new PageResult<>(rows, total, page);
    }
    public List<Question.Summary> candidates(QuestionTopic topic, InterviewDifficulty difficulty, String keyword) {
        return summaries(filter(topic, difficulty, keyword).orderByAsc("id"));
    }
    private QueryWrapper<QuestionEntity> filter(QuestionTopic topic, InterviewDifficulty difficulty, String keyword) {
        var query = new QueryWrapper<QuestionEntity>();
        query.eq("published", true);
        if (topic != null) query.eq("topic", topic.name());
        if (difficulty != null) query.eq("difficulty", difficulty.name());
        if (!keyword.isEmpty()) {
            String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.and(q -> q.apply("title LIKE {0} ESCAPE '!'", pattern)
                    .or().apply("question LIKE {0} ESCAPE '!'", pattern));
        }
        return query;
    }
    private List<Question.Summary> summaries(QueryWrapper<QuestionEntity> query) {
        // 只查询摘要字段，避免将答案解析带入题目列表响应。
        query.select("id", "title", "LEFT(question, 160) AS question", "topic", "competency_id", "difficulty", "tags_json", "version");
        return mapper.selectList(query).stream().map(e -> new Question.Summary(e.getId(), e.getTitle(), e.getQuestion(),
                QuestionTopic.valueOf(e.getTopic()), e.getCompetencyId(), InterviewDifficulty.valueOf(e.getDifficulty()),
                strings(e.getTagsJson()), e.getVersion())).toList();
    }
    public Optional<Question> find(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(e -> new Question(e.getId(), e.getTitle(), e.getQuestion(),
                QuestionTopic.valueOf(e.getTopic()), e.getCompetencyId(), InterviewDifficulty.valueOf(e.getDifficulty()),
                strings(e.getTagsJson()), strings(e.getKeyPointsJson()), e.getReferenceAnswer(),
                strings(e.getCommonMistakesJson()), strings(e.getFollowUpsJson()), Question.SourceType.valueOf(e.getSourceType()),
                e.getSourceTitle(), e.getSourceUrl(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion(), e.getPublished()));
    }
    public Optional<State> state(String id) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<QuestionEntity>()
                .select("id", "version", "published").eq("id", id)))
                .map(e -> new State(e.getVersion(), e.getPublished()));
    }
    public Optional<State> lockState(String id) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<QuestionEntity>()
                .select("id", "version", "published").eq("id", id).last("FOR SHARE")))
                .map(e -> new State(e.getVersion(), e.getPublished()));
    }
    private List<String> strings(String value) { return Arrays.asList(json.read(value, String[].class)); }
}

