package cn.edu.whu.zhizhida.auth.api;

import java.time.Instant;

public record AuthResponse(
                String token,
                Instant expiresAt,
                UserProfile user) {
}
