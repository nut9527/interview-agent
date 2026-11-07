package cn.edu.whu.zhizhida.auth.api;

import java.util.Optional;

public interface AuthService {

    AuthResponse register(AuthRequest request);

    AuthResponse login(AuthRequest request);


    Optional<AuthenticatedUser> authenticate(String token);

    void logout(String token);
}
