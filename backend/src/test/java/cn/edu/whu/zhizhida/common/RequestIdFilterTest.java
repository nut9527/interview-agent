package cn.edu.whu.zhizhida.common;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdBeforeAuthenticationAndCleansMdc() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/resumes");
        request.addHeader(RequestIdFilter.HEADER, "untrusted-client-value");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
            response.setStatus(401);
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[a-f0-9-]{36}");
        assertThat(MDC.get("requestId")).isNull();
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), next, (req, res) -> {});
        assertThat(next.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void restoresMdcEvenWhenDownstreamThrows() {
        MDC.put("requestId", "outer-context");
        try {
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(),
                    new MockHttpServletResponse(), (req, res) -> { throw new ServletException("failure"); }))
                    .isInstanceOf(ServletException.class);
            assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        } finally {
            MDC.remove("requestId");
        }
    }
}
