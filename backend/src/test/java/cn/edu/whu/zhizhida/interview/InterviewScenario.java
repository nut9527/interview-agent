package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

import static org.assertj.core.api.Assertions.assertThat;

abstract class InterviewScenario extends IntegrationTestBase {
    private final List<String> accounts = new ArrayList<>();

    String account(String displayName) throws Exception {
        String token = registerUniqueUserToken("password123", displayName);
        accounts.add(token);
        return token;
    }

    long ownerId(String token) throws Exception {
        var me = performGet("/api/users/me", token);
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        return ((Number) ((Map<?, ?>) parseJsonResult(me).get("data")).get("id")).longValue();
    }

    @AfterEach
    void deleteScenarioAccounts() throws Exception {
        for (var token : accounts) {
            var deleted = performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token);
            assertThat(deleted.getResponse().getStatus()).isEqualTo(200);
        }
    }
}
