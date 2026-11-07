package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.json.JsonMapper;

class InterviewRejectionDiagnosticsTest {
    private final JsonMapper json = new JsonMapper();
    private final DefaultInterviewAiService parser = new DefaultInterviewAiService(org.mockito.Mockito.mock(cn.edu.whu.zhizhida.ai.api.JsonChatClient.class), json, BusinessMetrics.disabled());

    @Test
    void missingWrongTypeAndBlankFieldsRemainDistinctAcrossBusinessAdapter() {
        assertRejection("{}", StructuredOutput.RejectionReason.MISSING_FIELD);
        assertRejection("{\"question\":42}", StructuredOutput.RejectionReason.TYPE_MISMATCH);
        assertRejection("{\"question\":\"   \"}", StructuredOutput.RejectionReason.EMPTY_VALUE);
        assertRejection(json.createObjectNode().put("question", "密".repeat(301)).toString(),
                StructuredOutput.RejectionReason.LIMIT_EXCEEDED);
    }

    private void assertRejection(String payload, StructuredOutput.RejectionReason reason) {
        var failure = catchThrowableOfType(InterviewReviewRejected.class,
                () -> parser.parseOpening(json.readTree(payload), ""));
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.fieldPath()).isEqualTo("question");
    }

    @Test
    void untrustedFieldNamesNeverBecomeDiagnosticFields() {
        var failure = StructuredOutput.invalid("private answer\nAuthorization: secret");
        var wrapped = new InterviewReviewRejected(failure);
        assertThat(wrapped.fieldPath()).isEqualTo("$");
        assertThat(wrapped.reason()).isEqualTo(StructuredOutput.RejectionReason.INVALID_VALUE);
    }
}
