package cn.edu.whu.zhizhida.health;

import java.time.Instant;
import java.util.List;

/** 显式定义健康响应结构，使其字段也纳入 OpenAPI 兼容性检查。 */
public record HealthResponse(String status, String service, String version, String storage,
                             String aiProvider, List<String> features, Instant time) {}
