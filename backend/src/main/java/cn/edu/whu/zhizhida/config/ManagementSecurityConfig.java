package cn.edu.whu.zhizhida.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 监控凭据与用户会话相互独立，未配置凭据时禁用监控访问。 */
@Configuration
public class ManagementSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain managementSecurity(HttpSecurity http,
            @Value("${management.api-token:}") String token) throws Exception {
        return http.securityMatcher("/actuator", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"monitoring authentication required\"}");
                }))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("MONITORING"))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        String header = request.getHeader("Authorization");
                        if (!token.isBlank() && header != null && header.length() <= 1024
                                && header.startsWith("Bearer ") && MessageDigest.isEqual(
                                token.getBytes(StandardCharsets.UTF_8), header.substring(7).getBytes(StandardCharsets.UTF_8))) {
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken("monitoring", null,
                                            List.of(new SimpleGrantedAuthority("MONITORING"))));
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class).build();
    }
}
