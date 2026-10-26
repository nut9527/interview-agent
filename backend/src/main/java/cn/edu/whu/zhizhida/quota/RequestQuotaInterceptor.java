package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 MVC 入口为注册、登录、演示创建和简历优化执行对应的额度准入。
 * 面试回答的额度在服务完成归属、回放和租约检查后扣减，避免成功重试重复扣额。
 */
@Component
public class RequestQuotaInterceptor implements HandlerInterceptor {
    private final RequestQuotaService quotas;
    private final int loginPerMinute;
    private final int loginPerDay;
    private final int demoPerHour;
    private final int demoPerDay;
    private final AiRequestQuota aiQuota;
    private final int registerPerMinute;
    private final int registerPerDay;

    public RequestQuotaInterceptor(RequestQuotaService quotas, AiRequestQuota aiQuota,
            @Value("${quota.register.per-minute:3}") int registerPerMinute,
            @Value("${quota.register.per-day:10}") int registerPerDay,
            @Value("${quota.login.per-minute:10}") int loginPerMinute,
            @Value("${quota.login.per-day:60}") int loginPerDay,
            @Value("${quota.demo.per-hour:6}") int demoPerHour,
            @Value("${quota.demo.per-day:6}") int demoPerDay) {
        this.quotas = quotas;
        this.loginPerMinute = loginPerMinute;
        this.loginPerDay = loginPerDay;
        this.demoPerHour = demoPerHour;
        this.demoPerDay = demoPerDay;
        this.aiQuota = aiQuota;
        this.registerPerMinute = registerPerMinute;
        this.registerPerDay = registerPerDay;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        List<RequestQuotaService.Charge> charges = new ArrayList<>();
        String ip = request.getRemoteAddr(); // 使用连接来源地址，不采信可伪造的转发请求头。
        if ("/api/auth/register".equals(path)) {
            charges.add(charge("register-minute", ip, 60, registerPerMinute, 1, "注册过于频繁，请稍后重试"));
            charges.add(charge("register-day", ip, 86400, registerPerDay, 1, "今日注册额度已用完"));
        } else if ("/api/auth/login".equals(path)) {
            charges.add(charge("login-minute", ip, 60, loginPerMinute, 1, "登录尝试过于频繁，请稍后重试"));
            charges.add(charge("login-day", ip, 86400, loginPerDay, 1, "今日登录尝试额度已用完"));
        } else if ("/api/auth/demo".equals(path)) {
            charges.add(charge("demo-hour", ip, 3600, demoPerHour, 1, "演示空间创建过于频繁，请稍后重试"));
            charges.add(charge("demo-day", ip, 86400, demoPerDay, 1, "今日演示空间创建额度已用完"));
        } else if (isAiGeneration(path)) {
            aiQuota.admit(userId(), AiRequestQuota.RESUME_OPTIMIZATION_UNITS);

        }
        if (!charges.isEmpty()) quotas.admit(charges);
        return true;
    }

    private static boolean isAiGeneration(String path) {
        // 面试回答在服务完成归属、历史回放与租约检查后执行额度准入。
        return "/api/ai/resume/optimize".equals(path);
    }

    private static String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId().toString();
        }
        // 安全链在进入控制器前拒绝未认证请求，此处不使用共享键作为回退身份。
        throw new IllegalStateException("Authenticated quota request has no user");
    }

    private static RequestQuotaService.Charge charge(String scope, String subject,
            long seconds, int max, int units, String message) {
        return new RequestQuotaService.Charge(scope, subject, seconds, max, max, units, message);
    }
}
