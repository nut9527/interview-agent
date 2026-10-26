package cn.edu.whu.zhizhida.interview;

import org.springframework.web.bind.annotation.RequestParam;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewStartRequest;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<InterviewHistoryItem>> listHistory(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(interviewService.listHistory(new PageRequest(page, size)));
    }


    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<InterviewSession> detail(@PathVariable String sessionId) {
        return ApiResponse.ok(interviewService.findSession(sessionId));
    }

    @PostMapping("/sessions")
    public ApiResponse<InterviewSession> start(@Valid @RequestBody InterviewStartRequest request) {
        return ApiResponse.ok(interviewService.start(request));
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public ApiResponse<InterviewSession> answer(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewAnswerRequest request
    ) {
        return ApiResponse.ok(interviewService.answer(sessionId, request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable String sessionId) {
        interviewService.deleteSession(sessionId);
        return ApiResponse.ok(null);
    }
}
