package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.learning.api.LearningGoal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/learning")
public class LearningGoalController {
    private final LearningGoalService goals;
    public LearningGoalController(LearningGoalService goals) { this.goals=goals; }
    @GetMapping("/goals") public ApiResponse<List<LearningGoal>> list() { return ApiResponse.ok(goals.list()); }
    @PostMapping("/goals") public ApiResponse<LearningGoal> create(@Valid @RequestBody LearningGoal.Create input) { return ApiResponse.ok(goals.create(input)); }
    @GetMapping("/goals/{id}") public ApiResponse<LearningGoal> get(@PathVariable String id) { return ApiResponse.ok(goals.get(id)); }
    @PostMapping("/goals/{id}/status") public ApiResponse<LearningGoal> change(@PathVariable String id,@Valid @RequestBody LearningGoal.Change input) { return ApiResponse.ok(goals.change(id,input)); }
    @PostMapping("/goals/{id}/plan-commands") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<ExecutionPort.Run> propose(@PathVariable String id,@Valid @RequestBody LearningGoal.Command input) { return ApiResponse.ok(goals.propose(id,input)); }
    @PostMapping("/goals/{id}/review") public ApiResponse<LearningGoal.Review> review(@PathVariable String id) { return ApiResponse.ok(goals.review(id)); }
    @PostMapping("/goals/{id}/criteria") public ApiResponse<LearningGoal> criteria(@PathVariable String id,@Valid @RequestBody LearningGoal.Criteria input) { return ApiResponse.ok(goals.criteria(id,input)); }
    @PostMapping("/goals/{id}/complete") public ApiResponse<LearningGoal> complete(@PathVariable String id,@Valid @RequestBody LearningGoal.Complete input) { return ApiResponse.ok(goals.complete(id,input)); }
    @PostMapping("/plans/{id}/revisions") public ApiResponse<LearningGoal.Plan> edit(@PathVariable String id,@Valid @RequestBody LearningGoal.Edit input) { return ApiResponse.ok(goals.edit(id,input)); }
    @PostMapping("/plans/{id}/reject") public ApiResponse<LearningGoal.Plan> reject(@PathVariable String id,@Valid @RequestBody LearningGoal.Revision input) { return ApiResponse.ok(goals.reject(id,input)); }
    @PostMapping("/plans/{id}/confirm") public ApiResponse<LearningGoal.Plan> confirm(@PathVariable String id,@Valid @RequestBody LearningGoal.Confirm input) { return ApiResponse.ok(goals.confirm(id,input)); }
}
