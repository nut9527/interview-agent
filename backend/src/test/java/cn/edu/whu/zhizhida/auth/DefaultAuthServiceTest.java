package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthRequest;
import cn.edu.whu.zhizhida.auth.api.AuthResponse;
import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAuthServiceTest {

    private static final Long USER_ID = 1L;
    private static final String RAW_PASSWORD = "password123";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedHashValue";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private DefaultAuthService authService;

    private UserAccount account;

    @BeforeEach
    void setUp() {
        authService = new DefaultAuthService(userRepository, sessionRepository, passwordEncoder);

        account = new UserAccount(USER_ID, "testuser", ENCODED_PASSWORD, "Test User",
                "Java Engineer", List.of("Java"));
    }

    // 账号注册

    @Test
    void registerCreatesUserAndReturnsTokenWithProfile() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, "Test User");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.create("testuser", ENCODED_PASSWORD, "Test User")).thenReturn(account);

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isNotNull().isNotEmpty();
        assertThat(response.user()).isNotNull();
        assertThat(response.user().id()).isEqualTo(USER_ID);
        assertThat(response.user().username()).isEqualTo("testuser");
        assertThat(response.user().displayName()).isEqualTo("Test User");
        assertThat(response.expiresAt()).isAfter(Instant.now());
        verify(sessionRepository).create(anyString(), any(), any());
        verify(userRepository).create("testuser", ENCODED_PASSWORD, "Test User");
    }

    @Test
    void registerNormalizesUsernameToLowercaseTrimmed() {
        AuthRequest request = new AuthRequest("  TestUser  ", RAW_PASSWORD, null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.create("testuser", ENCODED_PASSWORD, "testuser")).thenReturn(account);

        AuthResponse response = authService.register(request);

        assertThat(response.user().username()).isEqualTo("testuser");
        verify(userRepository).create("testuser", ENCODED_PASSWORD, "testuser");
    }

    @Test
    void registerThrowsConflictWhenUsernameExists() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名已存在");
    }

    @Test
    void registerUsesUsernameAsDisplayNameWhenDisplayNameBlank() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, "   ");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.create("testuser", ENCODED_PASSWORD, "testuser"))
                .thenReturn(accountWithDisplayName("testuser"));

        AuthResponse response = authService.register(request);

        assertThat(response.user().displayName()).isEqualTo("testuser");
        verify(userRepository).create("testuser", ENCODED_PASSWORD, "testuser");
    }

    @Test
    void registerUsesUsernameAsDisplayNameWhenDisplayNameNull() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.create("testuser", ENCODED_PASSWORD, "testuser"))
                .thenReturn(accountWithDisplayName("testuser"));

        AuthResponse response = authService.register(request);

        assertThat(response.user().displayName()).isEqualTo("testuser");
    }

    @Test
    void registerTrimsDisplayNameWhenProvided() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, "  My Display Name  ");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.create("testuser", ENCODED_PASSWORD, "My Display Name"))
                .thenReturn(accountWithDisplayName("My Display Name"));

        AuthResponse response = authService.register(request);

        assertThat(response.user().displayName()).isEqualTo("My Display Name");
    }

    // 账号登录

    @Test
    void loginReturnsTokenWhenCredentialsValid() {
        AuthRequest request = new AuthRequest("testuser", RAW_PASSWORD, null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);

        AuthResponse response = authService.login(request);

        assertThat(response.token()).isNotNull().isNotEmpty();
        assertThat(response.user()).isNotNull();
        assertThat(response.user().id()).isEqualTo(USER_ID);
        verify(sessionRepository).create(anyString(), any(), any());
    }

    @Test
    void loginThrowsUnauthorizedWhenPasswordWrong() {
        AuthRequest request = new AuthRequest("testuser", "wrongpassword", null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrongpassword", ENCODED_PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void loginThrowsUnauthorizedWhenUserNotFound() {
        AuthRequest request = new AuthRequest("nonexistent", RAW_PASSWORD, null);
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void loginSameErrorMessageForWrongPasswordAndNonExistentUser() {
        // 两种情况返回相同提示，避免暴露账号是否存在。
        AuthRequest wrongPw = new AuthRequest("testuser", "wrongpassword", null);
        AuthRequest noUser = new AuthRequest("nonexistent", RAW_PASSWORD, null);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrongpassword", ENCODED_PASSWORD)).thenReturn(false);
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        String wrongPwMsg = null;
        try {
            authService.login(wrongPw);
        } catch (BusinessException ex) {
            wrongPwMsg = ex.getMessage();
        }

        String noUserMsg = null;
        try {
            authService.login(noUser);
        } catch (BusinessException ex) {
            noUserMsg = ex.getMessage();
        }

        assertThat(wrongPwMsg).isEqualTo("用户名或密码错误");
        assertThat(noUserMsg).isEqualTo("用户名或密码错误");
        assertThat(wrongPwMsg).isEqualTo(noUserMsg);
    }

    @Test
    void loginNormalizesUsernameBeforeLookup() {
        AuthRequest request = new AuthRequest("  TestUser  ", RAW_PASSWORD, null);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);

        authService.login(request);

        verify(userRepository).findByUsername("testuser");
    }

    @Test
    void loginRejectsDemoTemplateCredentials() {
        UserAccount template = new UserAccount(USER_ID, "demo", ENCODED_PASSWORD, "演示用户",
                "Java Engineer", List.of("Java"), AccountType.DEMO_TEMPLATE, null);
        when(userRepository.findByUsername("demo")).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> authService.login(new AuthRequest("demo", RAW_PASSWORD, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(passwordEncoder).matches(org.mockito.ArgumentMatchers.eq(RAW_PASSWORD),
                org.mockito.ArgumentMatchers.argThat(hash -> hash.startsWith("$2a$")
                        && !hash.equals(ENCODED_PASSWORD)));
        verify(sessionRepository, never()).create(anyString(), any(), any());
    }

    @Test
    void demoSessionUsesPublicProfileAndSandboxExpiry() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        UserAccount sandbox = new UserAccount(USER_ID, "demo_internal", ENCODED_PASSWORD, "演示用户",
                "Java Engineer", List.of("Java"), AccountType.DEMO_SANDBOX,
                java.time.LocalDateTime.now().plusHours(1));


        AuthResponse response = authService.createSession(sandbox, expiresAt);

        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.user().username()).isEqualTo("demo");
        assertThat(response.user().demo()).isTrue();
        verify(sessionRepository).create(anyString(), org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(expiresAt));
    }

    // 会话认证

    @Test
    void authenticateReturnsUserForValidToken() {
        String token = "valid-session-token-123";
        String tokenHash = sha256(token);

        when(sessionRepository.findActiveUserId(tokenHash)).thenReturn(Optional.of(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));

        Optional<AuthenticatedUser> result = authService.authenticate(token);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_ID);
        assertThat(result.get().username()).isEqualTo("testuser");
        verify(sessionRepository).findActiveUserId(tokenHash);
    }

    @Test
    void authenticateReturnsEmptyWhenSessionNotFound() {
        String token = "invalid-token-456";
        String tokenHash = sha256(token);

        when(sessionRepository.findActiveUserId(tokenHash)).thenReturn(Optional.empty());

        Optional<AuthenticatedUser> result = authService.authenticate(token);

        assertThat(result).isEmpty();
        verify(userRepository, never()).findById(any());
    }

    @Test
    void authenticateReturnsEmptyWhenUserNotFoundAfterValidSession() {
        String token = "token-with-deleted-user";
        String tokenHash = sha256(token);

        when(sessionRepository.findActiveUserId(tokenHash)).thenReturn(Optional.of(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Optional<AuthenticatedUser> result = authService.authenticate(token);

        assertThat(result).isEmpty();
    }

    // 退出登录

    @Test
    void logoutDeletesSessionByTokenHash() {
        String token = "logout-token-789";
        String tokenHash = sha256(token);

        authService.logout(token);

        verify(sessionRepository).delete(tokenHash);
    }

    @Test
    void logoutDoesNotThrowWhenTokenIsNull() {
        assertThatCode(() -> authService.logout(null)).doesNotThrowAnyException();
        verify(sessionRepository, never()).delete(anyString());
    }

    // 摘要算法的确定性

    @Test
    void hashTokenIsDeterministic() {
        String token = "deterministic-token";
        String hash1 = sha256(token);
        String hash2 = sha256(token);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 摘要编码为 64 个十六进制字符。
    }

    @Test
    void hashTokenProducesDifferentResultsForDifferentTokens() {
        String hash1 = sha256("token-alpha");
        String hash2 = sha256("token-beta");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // 辅助方法

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private UserAccount accountWithDisplayName(String displayName) {
        return new UserAccount(USER_ID, "testuser", ENCODED_PASSWORD, displayName,
                "Java Engineer", List.of("Java"));
    }
}
