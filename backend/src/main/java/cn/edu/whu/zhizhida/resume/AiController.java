package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeResponse;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/resume")
public class AiController {

    private final ResumeDraftOptimizer draftOptimizer;

    public AiController(ResumeDraftOptimizer draftOptimizer) {
        this.draftOptimizer = draftOptimizer;
    }

    @PostMapping("/optimize-draft")
    public ApiResponse<ResumeDraftOptimizeResponse> optimizeDraft(@Valid @RequestBody ResumeDraftOptimizeRequest request) {
        return ApiResponse.ok(draftOptimizer.optimize(request));
    }
}
