package cn.edu.whu.zhizhida.health;

import cn.edu.whu.zhizhida.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
    private final ReadinessService readinessService;

    public ReadinessController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<ApiResponse<ReadinessStatus>> readiness() {
        boolean ready = readinessService.isReady();
        // 不公开连接地址、用户名或驱动异常，也不调用付费 AI 服务。
        var details = new ReadinessStatus("zhizhida-backend", ready ? "up" : "down", ready ? "up" : "down");
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(ready, ready ? "ok" : "数据库尚未就绪", details,
                        ready ? null : cn.edu.whu.zhizhida.common.ErrorCode.SERVICE_UNAVAILABLE));
    }

    public record ReadinessStatus(String service, String status, String database) {}
}
