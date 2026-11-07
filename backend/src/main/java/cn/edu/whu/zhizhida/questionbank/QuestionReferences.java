package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.ai.api.ReferenceToolClient;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.questionbank.api.Question.ReadingReference;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 只用已发布题目的公开字段检索。外部文档与题目原始解析分开加载。 */
@Service
public class QuestionReferences {
    private final QuestionService questions;
    private final Optional<ReferenceToolClient> client;
    private final ObjectMapper json;

    public QuestionReferences(QuestionService questions, Optional<ReferenceToolClient> client, ObjectMapper json) {
        this.questions = questions;
        this.client = client;
        this.json = json;
    }

    public List<ReadingReference> find(String questionId) {
        var question = questions.find(questionId);
        var reference = client.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "延伸阅读服务未启用"));
        var query = question.title() + " " + String.join(" ", question.tags());
        try {
            var result = reference.search(query.substring(0, Math.min(300, query.length())), 3);
            var hits = json.readValue(result, ReadingReference[].class);
            if (hits == null || hits.length > 3 || Arrays.stream(hits).anyMatch(hit -> !valid(hit))) {
                throw new IllegalArgumentException("Malformed reference result");
            }
            return List.of(hits);
        } catch (RunFailureException | JacksonException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "延伸阅读暂不可用，请稍后重试");
        }
    }

    private boolean valid(ReadingReference hit) {
        if (hit == null || hit.title() == null || hit.title().isBlank() || hit.summary() == null
                || hit.summary().isBlank() || hit.url() == null) return false;
        var url = URI.create(hit.url());
        return "https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null;
    }
}
