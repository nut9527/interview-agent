package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.AuthRequest;
import cn.edu.whu.zhizhida.auth.api.AuthResponse;
import cn.edu.whu.zhizhida.auth.api.AuthService;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    private final DemoSandboxService sandboxes;

    public AuthController(AuthService authService, DemoSandboxService sandboxes) {
        this.sandboxes = sandboxes;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/demo")
    public ApiResponse<AuthResponse> demoLogin() {
        return ApiResponse.ok(sandboxes.demoLogin());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(extractToken(request.getHeader("Authorization")));
        return ApiResponse.ok(null);
    }
    private static String extractToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
