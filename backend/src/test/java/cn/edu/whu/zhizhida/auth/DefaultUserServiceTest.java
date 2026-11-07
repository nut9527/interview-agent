package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.ChangePasswordRequest;
import cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest;
import cn.edu.whu.zhizhida.auth.api.UserProfile;
import cn.edu.whu.zhizhida.auth.api.UserProfileRequest;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.auth.SessionRepository;
import cn.edu.whu.zhizhida.auth.UserAccount;
import cn.edu.whu.zhizhida.auth.UserAccountRepository;
import cn.edu.whu.zhizhida.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;

    private PasswordEncoder passwordEncoder;
    private DefaultUserService userService;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        account = new UserAccount(
                USER_ID,
                "demo",
                passwordEncoder.encode("oldPass1"),
                "Demo User",
                "Java Engineer",
                List.of("Java")
        );
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        userService = new DefaultUserService(
                userRepository,
                sessionRepository,
                currentUserProvider,
                passwordEncoder
        );
    }

    @Test
    void shouldNormalizeAndDeduplicateSkillTags() {
        when(userRepository.updateProfile(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(UserProfileRequest.class)))
                .thenAnswer(invocation -> {
                    UserProfileRequest request = invocation.getArgument(1);
                    return new UserAccount(USER_ID, "demo", account.passwordHash(), request.displayName(),
                            request.targetRole(), request.skillTags());
                });

        UserProfile profile = userService.update(new UserProfileRequest(
                "  Demo User  ",
                "  Backend Engineer  ",
                List.of(" Java ", "java", "Spring Boot")
        ));

        assertThat(profile.displayName()).isEqualTo("Demo User");
        assertThat(profile.targetRole()).isEqualTo("Backend Engineer");
        assertThat(profile.skillTags()).containsExactly("Java", "Spring Boot");
    }

    @Test
    void shouldChangePasswordAndInvalidateEverySession() {
        stubCurrentAccount();
        userService.changePassword(new ChangePasswordRequest("oldPass1", "newSecure2"));

        ArgumentCaptor<String> passwordHash = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePassword(org.mockito.ArgumentMatchers.eq(USER_ID), passwordHash.capture());
        assertThat(passwordEncoder.matches("newSecure2", passwordHash.getValue())).isTrue();
        verify(sessionRepository).deleteByUserId(USER_ID);
    }

    @Test
    void shouldRejectIncorrectCurrentPassword() {
        stubCurrentAccount();
        assertThatThrownBy(() -> userService.changePassword(
                new ChangePasswordRequest("wrongPass1", "newSecure2")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前密码不正确");
    }

    @Test
    void shouldAuthorizeClosureAndDeleteIdentityWithSessions() {
        stubCurrentAccount();
        when(userRepository.deleteById(USER_ID)).thenReturn(true);

        assertThat(userService.authorizeClosure(new DeleteAccountRequest("oldPass1"))).isEqualTo(USER_ID);
        userService.deleteIdentity(USER_ID);

        verify(sessionRepository).deleteByUserId(USER_ID);
        verify(userRepository).deleteById(USER_ID);
    }

    @Test
    void shouldRejectNewPasswordSameAsCurrent() {
        stubCurrentAccount();
        assertThatThrownBy(() -> userService.changePassword(
                new ChangePasswordRequest("oldPass1", "oldPass1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("新密码不能与当前密码相同");
    }

    @Test
    void shouldThrowWhenDeleteAccountFails() {
        org.mockito.Mockito.lenient().when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.deleteById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteIdentity(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录状态已失效");
    }

    @Test
    void shouldThrowWhenCurrentAccountNotFound() {
        assertThatThrownBy(() -> userService.current())
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录状态已失效");
    }

    @Test
    void shouldHandleNullSkillTagsInUpdate() {
        when(userRepository.updateProfile(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(UserProfileRequest.class)))
                .thenAnswer(invocation -> {
                    UserProfileRequest request = invocation.getArgument(1);
                    return new UserAccount(USER_ID, "demo", account.passwordHash(), request.displayName(),
                            request.targetRole(), request.skillTags());
                });

        UserProfile profile = userService.update(new UserProfileRequest(
                "Demo User",
                "Backend Engineer",
                null
        ));

        assertThat(profile.skillTags()).isEmpty();
    }

    private void stubCurrentAccount() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
    }
}
