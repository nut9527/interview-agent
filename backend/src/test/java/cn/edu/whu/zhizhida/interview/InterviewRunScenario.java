package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunWorker;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

abstract class InterviewRunScenario extends InterviewScenario {
    @Autowired InterviewRunStore store;
    @Autowired InterviewRunWorker worker;
    String token;
    long owner;

    @BeforeEach
    void openScenarioAccount() throws Exception {
        token = account("面试回归");
        owner = ownerId(token);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dataMap(MvcResult response) throws Exception {
        return (Map<String, Object>) parseJsonResult(response).get("data");
    }

    String acceptStart(Map<String, Object> configuration) throws Exception {
        return acceptStart(UUID.randomUUID().toString(), configuration);
    }

    String acceptStart(String key, Map<String, Object> configuration) throws Exception {
        var result = performPost("/api/interviews/start-commands", Map.of("requestId", key, "configuration", configuration), token);
        return acceptedId(result);
    }

    String acceptAnswer(String session, String key, int turn, String answer) throws Exception {
        var result = performPost("/api/interviews/sessions/" + session + "/answer-commands",
                Map.of("requestId", key, "expectedTurn", turn, "answer", answer), token);
        return acceptedId(result);
    }

    private String acceptedId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return (String) dataMap(result).get("runId");
    }

    void run(String id) {
        var claim = store.claim(store.get(owner, id));
        assertThat(claim).isNotNull();
        worker.execute(claim);
    }

    void runSuccessfully(String id) {
        run(id);
        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
    }
}
