package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/interviews")
public class InterviewLearningController {
    private final InterviewResourceService resources;
    private final TrainingTaskService tasks;
    public InterviewLearningController(InterviewResourceService resources,TrainingTaskService tasks) { this.resources=resources;this.tasks=tasks; }
    @GetMapping("/sessions/{id}/resources")
    @io.swagger.v3.oas.annotations.Operation(operationId="interviewResources")
    public ApiResponse<List<InterviewResources>> resources(@PathVariable String id) { return ApiResponse.ok(resources.resources(id)); }
    @GetMapping("/training-tasks")
    @io.swagger.v3.oas.annotations.Operation(operationId="trainingTaskList")
    public ApiResponse<PageResult<TrainingTask>> tasks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(tasks.page(new PageRequest(page,size))); }
    @PostMapping("/training-tasks")
    @io.swagger.v3.oas.annotations.Operation(operationId="createTrainingTask")
    public ApiResponse<TrainingTask> create(@Valid @RequestBody TrainingTask.Create request) { return ApiResponse.ok(tasks.create(request)); }
    @PostMapping("/training-tasks/from-source")
    @io.swagger.v3.oas.annotations.Operation(operationId="createTrainingTaskFromSource")
    public ApiResponse<TrainingTask> fromSource(@Valid @RequestBody TrainingTask.DirectCreate request) { return ApiResponse.ok(tasks.createDirect(request)); }
    @PostMapping("/training-tasks/{id}/status")
    @io.swagger.v3.oas.annotations.Operation(operationId="updateTrainingTask")
    public ApiResponse<TrainingTask> update(@PathVariable String id,@Valid @RequestBody TrainingTask.Update request) { return ApiResponse.ok(tasks.update(id,request)); }
    @DeleteMapping("/training-tasks/{id}")
    @io.swagger.v3.oas.annotations.Operation(operationId="deleteTrainingTask")
    public ApiResponse<Void> delete(@PathVariable String id) { tasks.delete(id);return ApiResponse.ok(null); }
}
