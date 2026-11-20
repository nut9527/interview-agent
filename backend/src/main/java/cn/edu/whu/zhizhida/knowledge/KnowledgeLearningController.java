package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Confirm;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Feedback;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.FeedbackRequest;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Organization;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Organize;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.SelfTest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeLearningController {
    private final KnowledgeLearningService service;
    private final KnowledgeLearningRecords records;

    public KnowledgeLearningController(KnowledgeLearningService service, KnowledgeLearningRecords records) {
        this.service = service;
        this.records = records;
    }

    @GetMapping("/organization")
    public ApiResponse<List<Organization>> organization() {
        return ApiResponse.ok(records.organization());
    }

    @PutMapping("/organization")
    public ApiResponse<Void> organize(@Valid @RequestBody Organize request) {
        records.organize(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/self-tests/generate")
    public ApiResponse<List<SelfTest>> generate(@Valid @RequestBody Generate request) {
        return ApiResponse.ok(service.generate(request));
    }

    @GetMapping("/self-tests")
    public ApiResponse<PageResult<SelfTest>> questions(@RequestParam(defaultValue = "true") boolean confirmed,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(records.questions(confirmed, new PageRequest(page, size)));
    }

    @PostMapping("/self-tests/{id}/confirm")
    public ApiResponse<SelfTest> confirm(@PathVariable String id, @Valid @RequestBody Confirm request) {
        return ApiResponse.ok(records.confirm(id, request));
    }

    @DeleteMapping("/self-tests/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        records.deleteQuestion(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/answers/{id}/feedback")
    public ApiResponse<Feedback> feedback(@PathVariable String id) {
        return ApiResponse.ok(records.feedback(id));
    }

    @PutMapping("/answers/{id}/feedback")
    public ApiResponse<Feedback> feedback(@PathVariable String id, @Valid @RequestBody FeedbackRequest request) {
        return ApiResponse.ok(records.feedback(id, request));
    }

    @GetMapping("/feedback")
    public ApiResponse<PageResult<Feedback>> feedbacks(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(records.feedbacks(new PageRequest(page, size)));
    }
}
