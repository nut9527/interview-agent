package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 将有效 Bearer 令牌 对应的身份写入当前请求的安全上下文。
 * 无效或缺失的 令牌 保持未认证，由安全链根据接口权限决定是否拒绝访问。
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public BearerTokenFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request.getHeader("Authorization"));
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authService.authenticate(token).ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, token, List.of())
            ));
        }
        filterChain.doFilter(request, response);
    }

    public static String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
