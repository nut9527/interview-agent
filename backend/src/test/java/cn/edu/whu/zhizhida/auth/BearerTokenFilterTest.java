package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthService;
import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BearerTokenFilterTest {

    @Mock
    private AuthService authService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    private BearerTokenFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BearerTokenFilter(authService);
        SecurityContextHolder.clearContext();
    }

    // 令牌提取

    @Test
    void extractTokenReturnsNullForNullHeader() {
        assertThat(BearerTokenFilter.extractToken(null)).isNull();
    }

    @Test
    void extractTokenReturnsTokenAfterBearerPrefix() {
        assertThat(BearerTokenFilter.extractToken("Bearer my-secret-token")).isEqualTo("my-secret-token");
    }

    @Test
    void extractTokenReturnsNullForBasicAuth() {
        assertThat(BearerTokenFilter.extractToken("Basic dXNlcjpwYXNz")).isNull();
    }

    @Test
    void extractTokenReturnsNullForBearerWithEmptyToken() {
        assertThat(BearerTokenFilter.extractToken("Bearer ")).isNull();
    }

    @Test
    void extractTokenReturnsNullForBearerWithOnlyWhitespaceToken() {
        assertThat(BearerTokenFilter.extractToken("Bearer    ")).isNull();
    }

    @Test
    void extractTokenReturnsNullForEmptyString() {
        assertThat(BearerTokenFilter.extractToken("")).isNull();
    }

    @Test
    void extractTokenTrimsTokenValue() {
        assertThat(BearerTokenFilter.extractToken("Bearer   abc-123-token   ")).isEqualTo("abc-123-token");
    }

    @Test
    void extractTokenReturnsNullForHeaderWithoutBearerPrefix() {
        assertThat(BearerTokenFilter.extractToken("Token some-value")).isNull();
    }

    // 请求过滤与身份写入

    @Test
    void doFilterInternalSetsAuthenticationWhenValidToken() throws ServletException, IOException {
        String token = "valid-token";
        AuthenticatedUser user = new AuthenticatedUser(1L, "testuser");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(authService.authenticate(token)).thenReturn(Optional.of(user));

        SecurityContextHolder.createEmptyContext();

        filter.doFilterInternal(request, response, filterChain);

        verify(authService).authenticate(token);
        verify(filterChain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            assertThat(auth.getPrincipal()).isEqualTo(user);
        }
    }

    @Test
    void doFilterInternalSkipsWhenNoAuthorizationHeader() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(authService, never()).authenticate(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalSkipsWhenAlreadyAuthenticated() throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(request.getHeader("Authorization")).thenReturn("Bearer some-token");

        filter.doFilterInternal(request, response, filterChain);

        verify(authService, never()).authenticate(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalDoesNotSetAuthWhenTokenInvalid() throws ServletException, IOException {
        String token = "invalid-token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(authService.authenticate(token)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(authService).authenticate(token);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalAlwaysCallsFilterChain() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(authService.authenticate("token")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
