package cn.edu.whu.zhizhida.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

/**
 * 业务 接口 的认证与访问规则；用户身份由 Bearer 令牌 提供。
 * STATELESS 禁用的是 Servlet 会话，登录凭据仍由数据库会话表管理。
 */
@Configuration
public class SecurityConfig {
    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerTokenRegistration(BearerTokenFilter filter) {
        // 用户会话仅由业务安全链认证，管理端流量使用独立的认证规则。
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter) throws Exception {
        return http
                .cors(cors -> {
                })
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(SC_UNAUTHORIZED);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"请先登录\",\"data\":null,\"code\":\"AUTH_REQUIRED\"}");
                }).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"无权访问\",\"data\":null,\"code\":\"FORBIDDEN\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/register", "/api/auth/login", "/api/auth/demo", "/api/auth/logout",
                                "/api/health",
                                "/api/health/ready",
                                "/v3/api-docs/**",
                                "/doc.html",
                                "/webjars/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
