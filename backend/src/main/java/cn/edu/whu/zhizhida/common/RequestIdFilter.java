package cn.edu.whu.zhizhida.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

/**
 * 为请求生成服务端追踪编号，关联响应头与 MDC 日志，并记录脱敏后的访问摘要。
 * 在 finally 中恢复原 MDC，避免复用工作线程时串入其他请求的上下文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean completed = false;
        // 由服务端生成，避免客户端伪造编号或将敏感信息注入日志。
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            // 不记录查询参数、请求体、认证头或原始 URI。
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.info("HTTP request completed; method={} route={} status={} durationMs={}",
                    request.getMethod(), route == null ? "unmatched" : route,
                    completed ? response.getStatus() : 500, (System.nanoTime() - started) / 1_000_000);
            if (previous == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previous);
            }
        }
    }
}
