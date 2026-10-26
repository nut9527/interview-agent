package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.AiOptimizeResponse;

import cn.edu.whu.zhizhida.resume.api.AiOptimizeRequest;

import cn.edu.whu.zhizhida.resume.api.AiResumeService;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/resume")
public class AiController {

    private final AiResumeService aiResumeService;

    public AiController(AiResumeService aiResumeService) {
        this.aiResumeService = aiResumeService;
    }

    @PostMapping("/optimize")
    public ApiResponse<AiOptimizeResponse> optimize(@Valid @RequestBody AiOptimizeRequest request) {
        return ApiResponse.ok(aiResumeService.optimize(request));
    }
}
