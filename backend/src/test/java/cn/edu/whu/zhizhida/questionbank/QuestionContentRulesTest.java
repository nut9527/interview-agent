package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.QuestionSnapshot;
import cn.edu.whu.zhizhida.questionbank.domain.QuestionContentRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class QuestionContentRulesTest {
    private final JsonMapper mapper = new JsonMapper();
    private final QuestionSnapshot valid = new QuestionSnapshot("qb-java-001", 1, "线程安全",
            "共享计数器如何保证并发更新正确？", "JAVA", "foundation", InterviewDifficulty.STANDARD,
            List.of("Java"), List.of("原子性", "可见性", "验证"), "选择互斥或原子更新并用并发测试验证不变量。",
            List.of("将可见性当成原子性", "只测试单线程"), List.of("如何检查丢失更新？", "高竞争下有何取舍？"),
            "ORIGINAL", null, null);

    private QuestionSnapshot changed(Consumer<ObjectNode> mutation) {
        ObjectNode node = mapper.valueToTree(valid);
        mutation.accept(node);
        return mapper.treeToValue(node, QuestionSnapshot.class);
    }

    @Test void acceptsOriginalAndExplicitExternalReference() {
        assertThatCode(() -> QuestionContentRules.validate(valid)).doesNotThrowAnyException();
        assertThatCode(() -> QuestionContentRules.validate(changed(n -> n.put("sourceType", "EXTERNAL_REFERENCE")
                .put("sourceTitle", "技术说明").put("sourceUrl", "https://example.org/reference")))).doesNotThrowAnyException();
    }

    @Test void rejectsIncompleteAndOversizedText() {
        for (String field : List.of("id", "title", "question", "referenceAnswer")) {
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.putNull(field))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.put(field, "  "))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.put("title", "字".repeat(161)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsIncompatibleVersionDifficultyAndCompetency() {
        for (var mutation : List.<Consumer<ObjectNode>>of(n -> n.put("version", 0), n -> n.putNull("difficulty"),
                n -> n.put("competencyId", "storage"), n -> n.put("topic", "UNKNOWN")))
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(mutation))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsShortBlankDuplicateAndOversizedArrays() {
        for (String field : List.of("tags", "keyPoints", "commonMistakes", "followUps")) {
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.putNull(field))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.putArray(field))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.putArray(field).add("重复").add("重复").add("另一个"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> n.putArray(field).add(" ").add("要点二").add("要点三"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(n -> {
                var array = n.putArray(field);
                for (int i = 0; i < 31; i++) array.add("要点" + i);
            }))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsMissingOrUnsafeExternalAttribution() {
        for (var mutation : List.<Consumer<ObjectNode>>of(n -> n.put("sourceType", "FAKE"),
                n -> n.put("sourceType", "EXTERNAL_REFERENCE"),
                n -> n.put("sourceUrl", "javascript:alert(1)"), n -> n.put("sourceUrl", "https://example.org/a b")))
            assertThatThrownBy(() -> QuestionContentRules.validate(changed(mutation))).isInstanceOf(IllegalArgumentException.class);
    }
}
