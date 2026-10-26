package cn.edu.whu.zhizhida.auth.api;

import cn.edu.whu.zhizhida.auth.api.UserProfile;

import java.time.Instant;

public record AuthResponse(
        String token,
        Instant expiresAt,
        UserProfile user
) {
}
