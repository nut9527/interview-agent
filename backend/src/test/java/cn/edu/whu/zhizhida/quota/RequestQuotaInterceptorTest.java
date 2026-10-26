package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RequestQuotaInterceptorTest {
    private final RequestQuotaService quotas = mock(RequestQuotaService.class);
    private final RequestQuotaInterceptor interceptor = new RequestQuotaInterceptor(
            quotas, new AiRequestQuota(quotas, 5, 40, 400), 3, 10, 10, 60, 6, 6);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousLoginUsesRemoteAddressAndIgnoresForwardedHeader() {
        MockHttpServletRequest request = post("/api/auth/login");
        request.setRemoteAddr("198.51.100.4");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        List<RequestQuotaService.Charge> charges = captured();
        assertThat(charges).extracting(RequestQuotaService.Charge::subject)
                .containsExactly("198.51.100.4", "198.51.100.4");
        assertThat(charges).extracting(RequestQuotaService.Charge::scope)
                .containsExactly("login-minute", "login-day");
    }

    @Test
    void authenticatedOptimizationUsesUserIdAndGlobalBudget() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "user"), "token", List.of()));
        interceptor.preHandle(post("/api/ai/resume/optimize"),
                new MockHttpServletResponse(), new Object());
        assertThat(captured()).extracting(RequestQuotaService.Charge::subject)
                .containsExactly("42", "42", "all");

    }

    @Test
    void readOnlyRequestsDoNotSpendQuota() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/target-jobs");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        verify(quotas, never()).admit(anyList());
    }

    @Test
    void interviewAdmissionIsDeferredToServiceSoReplaysAreFree() {
        interceptor.preHandle(post("/api/interviews/sessions/session/answers"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(post("/api/interviews/sessions"), new MockHttpServletResponse(), new Object());
        verifyNoInteractions(quotas);
    }

    @Test
    void registrationHasIndependentAnonymousLimits() {
        interceptor.preHandle(post("/api/auth/register"), new MockHttpServletResponse(), new Object());
        assertThat(captured()).extracting(RequestQuotaService.Charge::scope)
                .containsExactly("register-minute", "register-day");
    }

    private MockHttpServletRequest post(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    @SuppressWarnings("unchecked")
    private List<RequestQuotaService.Charge> captured() {
        ArgumentCaptor<List<RequestQuotaService.Charge>> captor = ArgumentCaptor.forClass(List.class);
        verify(quotas).admit(captor.capture());
        return captor.getValue();
    }
}
