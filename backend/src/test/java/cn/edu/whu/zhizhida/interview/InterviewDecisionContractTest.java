package cn.edu.whu.zhizhida.interview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InterviewDecisionContractTest {
    private final JsonMapper json = new JsonMapper();
    private final String action = "{\"version\":\"phased-v1\",\"phase\":\"ACTION\",\"action\":\"COMPLETE\",\"reason\":\"材料充分\"}";
    private final String result = "{\"version\":\"phased-v1\",\"phase\":\"FINAL\",\"result\":{\"question\":\"问题\"}}";

    @Test void phasesCannotBeInterchangedAndPublicPayloadKeepsItsOriginalShape() {
        assertThat(InterviewDecisionContract.parse(json, action, "ACTION").path("action").asText()).isEqualTo("COMPLETE");
        assertThat(InterviewDecisionContract.parse(json, result, "FINAL")).isEqualTo(json.readTree("{\"question\":\"问题\"}"));
        assertThatThrownBy(() -> InterviewDecisionContract.parse(json, result, "ACTION")).isInstanceOf(InterviewReviewRejected.class);
        assertThatThrownBy(() -> InterviewDecisionContract.parse(json, action, "FINAL")).isInstanceOf(InterviewReviewRejected.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{broken", "{} {}",
            "{\"version\":\"phased-v1\",\"phase\":\"FINAL\",\"result\":null}",
            "{\"version\":\"phased-v1\",\"phase\":\"FINAL\",\"result\":{},\"action\":\"COMPLETE\"}",
            "{\"version\":\"future\",\"phase\":\"FINAL\",\"result\":{}}"})
    void malformedOrUnknownFinalEnvelopesAreRejected(String value) {
        assertThatThrownBy(() -> InterviewDecisionContract.parse(json, value, "FINAL")).isInstanceOf(InterviewReviewRejected.class);
    }

    @Test void unknownActionsCoercedReasonsAndUnboundedExplanationsAreRejected() {
        for (String value : new String[] {action.replace("COMPLETE", "ASK_USER"), action.replace("\"材料充分\"", "1"),
                action.replace("材料充分", " "), action.replace("材料充分", "x".repeat(241)), action.replace("\"ACTION\",", "\"ACTION\",\"assessment\":{},")})
            assertThatThrownBy(() -> InterviewDecisionContract.parse(json, value, "ACTION")).isInstanceOf(InterviewReviewRejected.class);
    }
}
