package cn.edu.whu.zhizhida;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ZhizhidaApplicationIT extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }

    @Test
    void monitoringIsDisabledWhenNoDedicatedTokenIsConfigured() throws Exception {
        assertThat(performGet("/actuator/health").getResponse().getStatus()).isEqualTo(401);
        assertThat(performGet("/actuator/health", "arbitrary-token").getResponse().getStatus()).isEqualTo(401);
    }

}
