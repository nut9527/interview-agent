package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SecurityCurrentUserProviderTest {

    private SecurityCurrentUserProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SecurityCurrentUserProvider();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireUserIdReturnsUserIdWhenAuthenticated() {
        AuthenticatedUser user = new AuthenticatedUser(42L, "testuser");
        Authentication auth = new UsernamePasswordAuthenticationToken(user, "token", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        Long userId = provider.requireUserId();

        assertThat(userId).isEqualTo(42L);
    }

    @Test
    void requireUserIdThrowsUnauthorizedWhenNoAuthentication() {
        assertThatThrownBy(() -> provider.requireUserId())
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> provider.requireUserId())
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先登录");
    }

    @Test
    void requireUserIdThrowsUnauthorizedWhenPrincipalIsWrongType() {
        // 当前身份为字符串，而非要求的 AuthenticatedUser 类型。
        Authentication auth = new UsernamePasswordAuthenticationToken("just-a-string", "token", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> provider.requireUserId())
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> provider.requireUserId())
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先登录");
    }

    @Test
    void requireUserIdThrowsUnauthorizedWhenAuthenticationIsNull() {
        SecurityContextHolder.clearContext();
        // 默认安全上下文不包含认证信息。

        BusinessException ex = catchThrowableOfType(
                () -> provider.requireUserId(),
                BusinessException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getMessage()).isEqualTo("请先登录");
    }
}
