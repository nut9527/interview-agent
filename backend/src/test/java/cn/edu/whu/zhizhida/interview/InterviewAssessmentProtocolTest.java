package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InterviewAssessmentProtocolTest {
    final JsonMapper json = new JsonMapper();
    final MockInterviewAiService parser = new MockInterviewAiService(json, BusinessMetrics.disabled());
    @Test void rejectsModelOwnedIdentityWrongEnumsUnknownFieldsAndCoercedOffsets() {
        String item = """
                {"dimension":"RESPONSIBILITY","status":"OBSERVED","claimText":"职责自述","claimStatus":"SUPPORTED",
                "limitation":"仅为自述","quotes":[{"offsetStart":0,"offsetEnd":1,"quote":"我"}]}
                """;
        for (String malformed : new String[] { "[]", "{\"ownerId\":7,\"items\":[]}",
                "{\"items\":[" + item.replace("RESPONSIBILITY", "TOTAL_SCORE") + "]}",
                "{\"items\":[" + item.replace("\"offsetStart\":0", "\"offsetStart\":\"0\"") + "]}",
                "{\"items\":[" + item.replace("\"offsetStart\":0", "\"offsetStart\":0.5") + "]}",
                "{\"items\":[" + item.replace("\"offsetStart\":0,", "") + "]}",
                "{\"items\":[" + item.replace("\"offsetStart\":0", "\"offsetStart\":null") + "]}" }) {
            var payload = json.createObjectNode().put("feedback", "收到回答").put("nextQuestion", "请继续说明依据？");
            payload.set("assessment", json.readTree(malformed));
            assertThatThrownBy(() -> parser.parseReview(payload, "", false)).isInstanceOf(InterviewReviewRejected.class);
        }
    }
    @Test void oldOutputIsExplicitlyUnassessedRatherThanInventingDimensions() {
        var payload = json.createObjectNode().put("feedback", "收到回答").put("nextQuestion", "请继续说明依据？");
        assertThat(parser.parseReview(payload, "", false).assessment()).isNull();
    }
    @Test void detailedSourceKeysCannotCarryAccountOverridesOrCoercedPositions() {
        String detail = """
                {"detail":{"protocolVersion":"six-dimensions-v1","items":[{"dimension":"RESPONSIBILITY","status":"OBSERVED",
                "claimText":"职责自述","claimStatus":"SUPPORTED","limitation":"仅为自述","majorCorrection":false,
                "citations":[{"sourceKey":"answer","offsetStart":0,"offsetEnd":1,"quote":"我","relation":"SELF_REPORT"}]}]}}
                """;
        for (String malformed : new String[] { detail.replace("RESPONSIBILITY", "TOTAL_SCORE"),
                detail.replace("\"sourceKey\":\"answer\"", "\"ownerId\":8,\"sourceKey\":\"answer\""),
                detail.replace("\"offsetStart\":0", "\"offsetStart\":\"0\""), detail.replace("\"offsetStart\":0,", ""),
                detail.replace("SELF_REPORT", "VERIFIED_FACT"), detail.replace("false", "\"false\"") }) {
            var payload = json.createObjectNode().put("feedback", "收到回答").put("nextQuestion", "请继续说明依据？");
            payload.set("assessment", json.readTree(malformed));
            assertThatThrownBy(() -> parser.parseReview(payload, "", false)).isInstanceOf(InterviewReviewRejected.class);
        }
    }
}
