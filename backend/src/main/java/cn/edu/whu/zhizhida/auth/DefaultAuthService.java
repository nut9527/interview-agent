package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.auth.api.AuthRequest;
import cn.edu.whu.zhizhida.auth.api.AuthResponse;
import cn.edu.whu.zhizhida.auth.api.AuthService;
import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.auth.api.UserProfile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

/**
 * 认证应用服务：统一处理注册、密码校验和数据库会话的签发、验证与撤销。
 * 演示模板和过期沙箱不作为有效登录身份；明文 令牌 仅在签发时返回客户端。
 */
@Service
public class DefaultAuthService implements AuthService {

    private static final Duration SESSION_DURATION = Duration.ofHours(24);
    // 未知用户名也执行一次 BCrypt 校验，避免通过响应耗时探测账号是否存在。
    private static final String DUMMY_PASSWORD_HASH = new BCryptPasswordEncoder().encode("unused-login-password");
    private final UserAccountRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;

    public DefaultAuthService(UserAccountRepository userRepository, SessionRepository sessionRepository,
                              PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public AuthResponse register(AuthRequest request) {
        String username = normalizeUsername(request.username());
        if (DefaultDemoAccounts.DEMO_USERNAME.equals(username)) {
            throw new BusinessException(ErrorCode.AUTH_USERNAME_UNAVAILABLE, "该用户名不可用");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCode.AUTH_USERNAME_UNAVAILABLE, "用户名已存在");
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? username
                : request.displayName().trim();
        UserAccount account;
        try {
            account = userRepository.create(username, passwordEncoder.encode(request.password()), displayName);
        } catch (DuplicateKeyException ex) {
            // 前置查询仅用于提前提示，查询后的并发注册最终由数据库唯一约束裁决。
            throw new BusinessException(ErrorCode.AUTH_USERNAME_UNAVAILABLE, "用户名已存在");
        }
        return createSession(account);
    }

    @Override
    public AuthResponse login(AuthRequest request) {
        UserAccount account = userRepository.findByUsername(normalizeUsername(request.username())).orElse(null);
        String passwordHash = account != null && account.accountType() == AccountType.STANDARD
                ? account.passwordHash() : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (account == null || account.accountType() != AccountType.STANDARD || !passwordMatches) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
        }
        return createSession(account);
    }


    @Override
    public Optional<AuthenticatedUser> authenticate(String token) {
        return sessionRepository.findActiveUserId(hashToken(token))
                .flatMap(userRepository::findById)
                .filter(account -> account.accountType() != AccountType.DEMO_TEMPLATE)
                .filter(account -> !account.isExpiredDemoSandbox(java.time.LocalDateTime.now()))
                .map(account -> new AuthenticatedUser(account.id(), account.username()));
    }

    @Override
    public void logout(String token) {
        if (token != null) {
            sessionRepository.delete(hashToken(token));
        }
    }

    private AuthResponse createSession(UserAccount account) {
        return createSession(account, Instant.now().plus(SESSION_DURATION));
    }

    AuthResponse createSession(UserAccount account, Instant expiresAt) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        // 数据库只保存摘要；后续认证与退出均对传入 令牌 使用相同算法查找会话。
        sessionRepository.create(hashToken(token), account.id(), expiresAt);
        return new AuthResponse(token, expiresAt, toProfile(account));
    }

    private UserProfile toProfile(UserAccount account) {
        return new UserProfile(account.id(), account.publicUsername(), account.displayName(),
                account.targetRole(), account.skillTags(), account.isDemo());
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
