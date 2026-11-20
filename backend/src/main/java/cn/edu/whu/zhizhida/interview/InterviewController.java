package cn.edu.whu.zhizhida.interview;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<InterviewHistoryItem>> listHistory(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(interviewService.listHistory(new PageRequest(page, size)));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<InterviewSession> detail(@PathVariable String sessionId) {
        return ApiResponse.ok(interviewService.findSession(sessionId).publicView());
    }

    @GetMapping("/sessions/{sessionId}/retries")
    public ApiResponse<PageResult<InterviewHistoryItem>> retries(@PathVariable String sessionId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(interviewService.listRetries(sessionId, new PageRequest(page, size)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @io.swagger.v3.oas.annotations.Operation(operationId = "delete_2")
    public ApiResponse<Void> delete(@PathVariable String sessionId) {
        interviewService.deleteSession(sessionId);
        return ApiResponse.ok(null);
    }
}
