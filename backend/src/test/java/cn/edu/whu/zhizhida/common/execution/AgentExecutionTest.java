package cn.edu.whu.zhizhida.common.execution;

import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AgentExecutionTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "42", "{} {}", "{\"query\":1} trailing"})
    void argumentsRequireExactlyOneObject(String raw) {
        var json = new JsonMapper();
        var call = json.createObjectNode();
        call.putObject("function").put("arguments", raw);
        assertThatThrownBy(() -> AgentExecution.arguments(json, call)).hasMessage("TOOL_ARGUMENTS");
    }

    @Test void exceptionTextCannotBecomeAnInternalFailureCode() {
        assertThat(AgentExecution.modelFailureCode(new IllegalStateException("MODEL_NETWORK private payload")))
                .isEqualTo("MODEL_UNAVAILABLE");
        assertThat(AgentExecution.modelFailureCode(UpstreamServiceException.coded("MODEL_NETWORK")))
                .isEqualTo("MODEL_NETWORK");
    }
}
