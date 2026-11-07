package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.questionbank.api.*;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {
    private final QuestionService service;
    private final QuestionReferences references;

    public QuestionController(QuestionService service, QuestionReferences references) {
        this.service = service;
        this.references = references;
    }

    @GetMapping
    public ApiResponse<PageResult<Question.Summary>> listQuestions(
            @Parameter(schema = @Schema(minimum = "1", maximum = "1000000")) @RequestParam(defaultValue = "1") int page,
            @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) QuestionTopic topic,
            @RequestParam(required = false) InterviewDifficulty difficulty,
            @Parameter(schema = @Schema(maxLength = 100)) @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.page(new PageRequest(page, size), topic, difficulty, keyword));
    }
    @GetMapping("/metadata")
    public ApiResponse<Question.Metadata> questionMetadata() { return ApiResponse.ok(service.metadata()); }
    @GetMapping("/{id}")
    public ApiResponse<Question> questionDetail(@PathVariable String id) { return ApiResponse.ok(service.find(id)); }
    @GetMapping("/{id}/references")
    public ApiResponse<java.util.List<Question.ReadingReference>> questionReferences(@PathVariable String id) {
        return ApiResponse.ok(references.find(id));
    }
    @GetMapping("/practice-status")
    public ApiResponse<java.util.List<cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory.Progress>> questionPracticeStatus() {
        return ApiResponse.ok(service.progress());
    }
    @GetMapping("/{id}/practices")
    public ApiResponse<PageResult<cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory.Practice>> questionPracticeHistory(
            @PathVariable String id,
            @Parameter(schema = @Schema(minimum = "1", maximum = "1000000")) @RequestParam(defaultValue="1") int page,
            @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue="10") int size) {
        return ApiResponse.ok(service.history(id, new PageRequest(page,size)));
    }
    @GetMapping("/draw")
    public ApiResponse<Question.Draw> drawQuestions(@RequestParam(required=false) QuestionTopic topic,
            @RequestParam(required=false) InterviewDifficulty difficulty,
            @Parameter(schema = @Schema(maxLength = 100)) @RequestParam(required=false) String keyword,
            @Parameter(schema = @Schema(minimum = "1", maximum = "10")) @RequestParam(defaultValue="3") int count,
            @Parameter(schema = @Schema(minimum = "3", maximum = "6")) @RequestParam(defaultValue="3") int roundsPerQuestion) {
        return ApiResponse.ok(service.draw(topic,difficulty,keyword,count,roundsPerQuestion));
    }
}

