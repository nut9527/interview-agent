package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.api.TargetJobRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/target-jobs")
public class TargetJobController {
    private final TargetJobService service;
    public TargetJobController(TargetJobService service) { this.service = service; }
    @GetMapping
    public ApiResponse<PageResult<TargetJob.Summary>> page(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.page(new PageRequest(page, size)));
    }
    @GetMapping("/{id}")
    public ApiResponse<TargetJob> find(@PathVariable String id) { return ApiResponse.ok(service.find(id)); }
    @PostMapping("/extract")
    public ApiResponse<TargetJob.Extraction> extract(@Valid @RequestBody TargetJob.ExtractRequest request) { return ApiResponse.ok(service.extract(request.jdText())); }
    @PostMapping
    public ApiResponse<TargetJob> create(@Valid @RequestBody TargetJobRequest request) { return ApiResponse.ok(service.create(request)); }
    @PutMapping("/{id}")
    public ApiResponse<TargetJob> update(@PathVariable String id, @Valid @RequestBody TargetJobRequest request) { return ApiResponse.ok(service.update(id, request)); }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestParam long revision) { service.delete(id, revision); return ApiResponse.ok(null); }
    @PostMapping("/{id}/analysis")
    public ApiResponse<TargetJob.Analysis> analyze(@PathVariable String id, @Valid @RequestBody TargetJob.AnalyzeRequest request) {
        return ApiResponse.ok(service.analyze(id, request));
    }
}
