package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeLearningController {
    private final KnowledgeLearningService service;
    private final KnowledgeLearningRecords records;
    public KnowledgeLearningController(KnowledgeLearningService service,KnowledgeLearningRecords records) { this.service=service;this.records=records; }
    @GetMapping("/organization") public ApiResponse<List<Organization>> organization() { return ApiResponse.ok(records.organization()); }
    @PutMapping("/organization") public ApiResponse<Void> organize(@Valid @RequestBody Organize request) { records.organize(request);return ApiResponse.ok(null); }
    @PostMapping("/self-tests/generate") public ApiResponse<List<SelfTest>> generate(@Valid @RequestBody Generate request) { return ApiResponse.ok(service.generate(request)); }
    @GetMapping("/self-tests") public ApiResponse<PageResult<SelfTest>> questions(@RequestParam(defaultValue="true") boolean confirmed,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(records.questions(confirmed,new PageRequest(page,size))); }
    @PostMapping("/self-tests/{id}/confirm") public ApiResponse<SelfTest> confirm(@PathVariable String id,@Valid @RequestBody Confirm request) { return ApiResponse.ok(records.confirm(id,request)); }
    @DeleteMapping("/self-tests/{id}") public ApiResponse<Void> delete(@PathVariable String id) { records.deleteQuestion(id);return ApiResponse.ok(null); }
    @GetMapping("/answers/{id}/feedback") public ApiResponse<Feedback> feedback(@PathVariable String id) { return ApiResponse.ok(records.feedback(id)); }
    @PutMapping("/answers/{id}/feedback") public ApiResponse<Feedback> feedback(@PathVariable String id,@Valid @RequestBody FeedbackRequest request) { return ApiResponse.ok(records.feedback(id,request)); }
    @GetMapping("/feedback") public ApiResponse<PageResult<Feedback>> feedbacks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(records.feedbacks(new PageRequest(page,size))); }
}
