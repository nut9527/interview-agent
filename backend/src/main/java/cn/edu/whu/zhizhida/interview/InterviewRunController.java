package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewRun.InterviewRunTrace;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunEvents;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
public class InterviewRunController {
    private final InterviewRunService runs;
    private final InterviewRunEvents events;
    private final TrainingTaskRunService tasks;

    public InterviewRunController(InterviewRunService runs, InterviewRunEvents events, TrainingTaskRunService tasks) {
        this.runs = runs;
        this.events = events;
        this.tasks = tasks;
    }

    @PostMapping("/api/interviews/training-tasks/{id}/start-commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InterviewRun> taskCommand(@PathVariable String id,
            @Valid @RequestBody InterviewRun.TaskStart command) {
        return ApiResponse.ok(tasks.start(id, command));
    }

    @PostMapping("/api/interviews/start-commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InterviewRun> startCommand(@Valid @RequestBody InterviewRun.Start request) {
        return ApiResponse.ok(runs.start(request));
    }

    @PostMapping("/api/interviews/sessions/{id}/answer-commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InterviewRun> answerCommand(@PathVariable String id,
            @Valid @RequestBody InterviewRun.Answer request) {
        return ApiResponse.ok(runs.answer(id, request));
    }

    @GetMapping("/api/interview-runs/{id}")
    public ApiResponse<InterviewRun> runStatus(@PathVariable String id) {
        return ApiResponse.ok(runs.get(id));
    }

    @GetMapping("/api/interview-runs/{id}/steps")
    public ApiResponse<InterviewRunTrace> runSteps(@PathVariable String id) {
        return ApiResponse.ok(runs.trace(id));
    }

    @GetMapping("/api/interviews/sessions/{id}/runs")
    public ApiResponse<List<InterviewRun>> sessionRuns(@PathVariable String id) {
        return ApiResponse.ok(runs.forSession(id));
    }

    @GetMapping("/api/interview-runs")
    public ApiResponse<List<InterviewRun>> activeRuns() {
        return ApiResponse.ok(runs.active());
    }

    @GetMapping("/api/interview-runs/by-command")
    public ApiResponse<InterviewRun> runByCommand(@RequestParam String operation, @RequestParam String requestId) {
        return ApiResponse.ok(runs.byCommand(operation, requestId));
    }

    @PostMapping("/api/interview-runs/{id}/cancel")
    public ApiResponse<InterviewRun> cancelRun(@PathVariable String id) {
        return ApiResponse.ok(runs.cancel(id));
    }

    @GetMapping(value = "/api/interview-runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runEvents(@PathVariable String id, @RequestParam(defaultValue = "0") long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastId) {
        if (afterSeq < 0 || lastId != null && lastId < 0) {
            throw new UserInputException("无效事件游标");
        }
        long owner = runs.owner(id);
        long cursor = Math.max(afterSeq, lastId == null ? 0 : lastId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store")
                .header("X-Accel-Buffering", "no")
                .body(events.subscribe(owner, id, cursor));
    }
}
