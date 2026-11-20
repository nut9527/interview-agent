package cn.edu.whu.zhizhida.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;

@RestController
public class ReadinessController {
    private final ReadinessService readinessService;
    private final MiddlewareReadiness middleware;

    public ReadinessController(ReadinessService readinessService, MiddlewareReadiness middleware) {
        this.readinessService = readinessService;
        this.middleware = middleware;
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<ApiResponse<ReadinessStatus>> readiness() {
        boolean databaseReady = readinessService.isReady();
        var dependencies = middleware.inspect();
        boolean ready = databaseReady && dependencies.acceptsRequests();
        // 不公开连接地址、用户名或驱动异常，也不调用付费 AI 服务。
        var details = new ReadinessStatus("zhizhida-backend",
                ready ? (dependencies.degraded() ? "degraded" : "up") : "down",
                databaseReady ? "up" : "down", dependencies);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(ready, ready ? "ok" : "请求受理依赖尚未就绪", details,
                        ready ? null : cn.edu.whu.zhizhida.common.ErrorCode.SERVICE_UNAVAILABLE));
    }

    public record ReadinessStatus(String service, String status, String database,
            MiddlewareReadiness.Status middleware) {
    }
}
