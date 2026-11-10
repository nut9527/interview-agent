package cn.edu.whu.zhizhida.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputTest {
    @ParameterizedTest
    @ValueSource(strings = {"ownerName", "skills", "startDate", "contributions"})
    void preservesKnownResumeFieldsAndSemanticRejectionReason(String field) {
        var exception = StructuredOutput.invalid(field, StructuredOutput.RejectionReason.SEMANTIC_MISMATCH);

        assertThat(exception.fieldPath()).isEqualTo(field);
        assertThat(exception.reason()).isEqualTo(StructuredOutput.RejectionReason.SEMANTIC_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"privateField", "candidate@example.test", "projects[0].name"})
    void hidesUnknownAndDynamicFieldsWithoutLosingRejectionReason(String field) {
        var exception = StructuredOutput.invalid(field, StructuredOutput.RejectionReason.TYPE_MISMATCH);

        assertThat(exception.fieldPath()).isEqualTo("$");
        assertThat(exception.reason()).isEqualTo(StructuredOutput.RejectionReason.TYPE_MISMATCH);
    }

    @Test
    void keepsTypeValidationForResumeFields() {
        var payload = new JsonMapper().readTree("{\"phone\":13800000000}");

        assertThatThrownBy(() -> StructuredOutput.text(payload, "phone", 40))
                .isInstanceOfSatisfying(StructuredOutput.FieldValidationException.class, exception -> {
                    assertThat(exception.fieldPath()).isEqualTo("phone");
                    assertThat(exception.reason()).isEqualTo(StructuredOutput.RejectionReason.TYPE_MISMATCH);
                });
    }
}
