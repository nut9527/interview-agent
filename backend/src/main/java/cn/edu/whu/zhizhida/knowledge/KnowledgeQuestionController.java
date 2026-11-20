package cn.edu.whu.zhizhida.knowledge;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeQuestionController {
    private final KnowledgeQuestionService service;
    private final KnowledgeAnswerHistory history;

    public KnowledgeQuestionController(KnowledgeQuestionService service, KnowledgeAnswerHistory history) {
        this.service = service;
        this.history = history;
    }

    @PostMapping("/answers")
    public ApiResponse<KnowledgeAnswer> ask(@Valid @RequestBody KnowledgeAnswer.Request request) {
        return ApiResponse.ok(service.ask(request));
    }

    @GetMapping("/answers")
    public ApiResponse<PageResult<KnowledgeAnswerRecord.Summary>> answerHistory(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(history.page(new PageRequest(page, size)));
    }

    @GetMapping("/answers/{id}")
    public ApiResponse<KnowledgeAnswerRecord> savedAnswer(@PathVariable String id) {
        return ApiResponse.ok(history.find(id));
    }

    @DeleteMapping("/answers/{id}")
    public ApiResponse<Void> deleteAnswer(@PathVariable String id) {
        history.delete(id);
        return ApiResponse.ok(null);
    }
}
