package cn.edu.whu.zhizhida.health;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String aiProvider;
    private final String version;

    public HealthController(@Value("${ai.provider:mock}") String aiProvider,
            @Value("${application.version:development}") String version) {
        this.aiProvider = aiProvider;
        this.version = version;
    }

    @GetMapping
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("up", "zhizhida-backend", version, "mysql", aiProvider,
                List.of("resume", "ai-optimize", "target-jobs", "interview"), Instant.now()));
    }
}
