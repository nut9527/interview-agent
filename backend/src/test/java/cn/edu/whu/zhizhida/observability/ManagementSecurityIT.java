package cn.edu.whu.zhizhida.observability;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {"management.api-token=integration-monitor-only",
        "management.endpoints.web.exposure.include=health,metrics,prometheus"})
@AutoConfigureMetrics
class ManagementSecurityIT extends IntegrationTestBase {
    @Autowired BusinessMetrics metrics;

    @Test
    void userTokensCannotReadMonitoringAndMonitoringTokensCannotReadUserData() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "monitor-test");
        assertThat(performGet("/actuator/prometheus").getResponse().getStatus()).isEqualTo(401);
        assertThat(performGet("/actuator/prometheus", token).getResponse().getStatus()).isEqualTo(401);
        assertThat(performGet("/api/resumes", "integration-monitor-only").getResponse().getStatus()).isEqualTo(401);
        assertThat(performGet("/actuator/env", "integration-monitor-only").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void prometheusIncludesBoundedBusinessAndPoolMetrics() throws Exception {
        metrics.record(BusinessMetrics.Operation.INTERVIEW_ANSWER, BusinessMetrics.Outcome.SUCCESS, 5_000_000);
        var result = performGet("/actuator/prometheus", "integration-monitor-only");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("zhizhida_business_seconds_count", "operation=\"interview_answer\"", "hikaricp_connections")
                .doesNotContain("integration-monitor-only", "password123", "requestId=");
    }
}
