package cn.edu.whu.zhizhida.evaluation;

import cn.edu.whu.zhizhida.evaluation.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DetailedEvaluationEngineTest {
    final EvaluationEngine engine = new EvaluationEngine();
    static final String ANSWER = "我负责订单接口。因为重试可能重复写入，我选唯一约束。";
    static final Instant TIME = Instant.parse("2026-10-31T00:00:00Z");
    Item unknown(Dimension dimension) { return new Item(dimension, Observation.NOT_OBSERVED, "未观察到", ClaimStatus.INSUFFICIENT, "等待补充", false, List.of()); }
    DetailedAssessmentDraft draft(Item... replacements) {
        var items = new ArrayList<>(Arrays.stream(Dimension.values()).map(this::unknown).toList());
        for (var item : replacements) items.set(item.dimension().ordinal(), item);
        return new DetailedAssessmentDraft("six-dimensions-v1", items);
    }
    Citation answer() { return new Citation("answer", 0, 7, ANSWER.substring(0, 7), Relation.SELF_REPORT); }
    Citation reference(String key, Relation relation) { return new Citation(key, 0, 3, "唯一键", relation); }
    Item observed(Dimension dimension, boolean correction, ClaimStatus status, Citation... quotes) {
        return new Item(dimension, Observation.OBSERVED, "回答描述具体行为与约束", status, "未独立验证", correction, Arrays.asList(quotes));
    }
    AssessmentReference ref(String key) {
        String text = "唯一键能约束重复请求，但应核对冲突后的返回结果。";
        return new AssessmentReference(key, 7, "KNOWLEDGE_EXCERPT", "doc/chunk", "v2@doc-hash", text,
                EvaluationEngine.hash(text), Normalization.RAW_UTF16, Validity.VALID);
    }
    AssessmentResult evaluate(DetailedAssessmentDraft draft, List<AssessmentReference> refs) {
        return engine.evaluateAnswer(7, "session:answer:1", ANSWER, TIME, new AssessmentDraft(null, draft), "controlled", refs);
    }
    @Test void sixIndependentDimensionsDoNotInventEvidenceForUncoveredSkills() {
        var result = evaluate(draft(observed(Dimension.RESPONSIBILITY, false, ClaimStatus.SUPPORTED, answer()),
                observed(Dimension.CAUSAL_EXPLANATION, false, ClaimStatus.SUPPORTED,
                        new Citation("answer", 8, ANSWER.length(), ANSWER.substring(8), Relation.SELF_REPORT))), List.of());
        assertThat(result.detail().items()).hasSize(6);
        assertThat(result.detail().rubric().anchors()).hasSize(6);
        assertThat(result.detail().evidence()).hasSize(2);
        assertThat(result.items()).allMatch(i -> i.status() == Observation.NOT_OBSERVED); // 旧版界面显示未知状态，不凭空换算为分数。
        assertThat(result.detail().items().get(Dimension.TECHNICAL_CORRECTNESS.ordinal()).technicalReview()).isEqualTo("INSUFFICIENT");
    }
    @Test void technicalObservationNeedsBothAnswerAndAuthorizedReference() {
        var technical = observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, answer(), reference("notes", Relation.SUPPORT));
        var result = evaluate(draft(technical), List.of(ref("notes"))).detail();
        var item = result.items().get(1);
        assertThat(item.technicalReview()).isEqualTo("REVIEW_REQUIRED");
        assertThat(item.claim().semanticCheck()).isEqualTo("MODEL_PROPOSED");
        assertThat(result.evidence()).extracting(Evidence::sourceType).containsExactly("ANSWER_SELF_REPORT", "KNOWLEDGE_EXCERPT");
        assertThatThrownBy(() -> evaluate(draft(observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, answer())), List.of()))
                .hasMessageContaining("对照资料");
        assertThatThrownBy(() -> evaluate(draft(observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, reference("notes", Relation.SUPPORT))), List.of(ref("notes"))))
                .hasMessageContaining("本轮回答");
    }
    @Test void counterevidenceCannotBeSilencedByProposedSupportedStatus() {
        var result = evaluate(draft(observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, answer(),
                reference("a", Relation.SUPPORT), reference("b", Relation.COUNTEREVIDENCE))), List.of(ref("a"), ref("b"))).detail();
        assertThat(result.items().get(1).claim().status()).isEqualTo(ClaimStatus.DISPUTED);
        assertThat(result.items().get(1).claim().limitation()).contains("争议");
    }
    @Test void groundedMajorCorrectionStillRequiresIndependentReview() {
        var result = evaluate(draft(observed(Dimension.TECHNICAL_CORRECTNESS, true, ClaimStatus.CONTRADICTED,
                answer(), reference("q", Relation.COUNTEREVIDENCE))), List.of(new AssessmentReference("q", 7, "QUESTION_REFERENCE", "q1", "2",
                        ref("q").text(), ref("q").sha256(), Normalization.RAW_UTF16, Validity.VALID))).detail();
        assertThat(result.items().get(1).claim().status()).isEqualTo(ClaimStatus.DISPUTED);
        assertThat(result.items().get(1).majorCorrection()).isTrue();
        assertThatThrownBy(() -> evaluate(draft(observed(Dimension.TECHNICAL_CORRECTNESS, true, ClaimStatus.CONTRADICTED, answer())), List.of()))
                .hasMessageContaining("对照资料");
    }
    @Test void noSourceCanImpersonateCandidateImplementationOrIndependentAnswerProof() {
        for (var item : List.of(observed(Dimension.RESPONSIBILITY, false, ClaimStatus.SUPPORTED, reference("notes", Relation.SUPPORT)),
                observed(Dimension.RESPONSIBILITY, false, ClaimStatus.SUPPORTED, new Citation("answer", 0, 7, ANSWER.substring(0, 7), Relation.SUPPORT)),
                observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, answer(), reference("notes", Relation.SELF_REPORT))))
            assertThatThrownBy(() -> evaluate(draft(item), List.of(ref("notes")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void identityRevocationSourceTypeAndHashMismatchFailClosed() {
        var r = ref("notes");
        var item = observed(Dimension.TECHNICAL_CORRECTNESS, false, ClaimStatus.SUPPORTED, answer(), reference("notes", Relation.SUPPORT));
        var variants = List.of(
                new AssessmentReference(r.key(), 8, r.sourceType(), r.sourceId(), r.version(), r.text(), r.sha256(), r.normalization(), r.validity()),
                new AssessmentReference(r.key(), 7, r.sourceType(), r.sourceId(), r.version(), r.text(), r.sha256(), r.normalization(), Validity.REVOKED),
                new AssessmentReference(r.key(), 7, "RESUME", r.sourceId(), r.version(), r.text(), r.sha256(), r.normalization(), r.validity()),
                new AssessmentReference(r.key(), 7, r.sourceType(), r.sourceId(), r.version(), r.text() + "changed", r.sha256(), r.normalization(), r.validity()),
                new AssessmentReference(r.key(), 7, r.sourceType(), r.sourceId(), r.version(), r.text(), r.sha256(), Normalization.LF_V1, r.validity()),
                new AssessmentReference(r.key(), 7, r.sourceType(), r.sourceId(), "", r.text(), r.sha256(), r.normalization(), r.validity()),
                new AssessmentReference(r.key(), 7, r.sourceType(), r.sourceId(), r.version(), null, r.sha256(), r.normalization(), r.validity()));
        for (var invalid : variants) assertThatThrownBy(() -> evaluate(draft(item), List.of(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(draft(item), List.of())).hasMessageContaining("固定范围");
    }
    @Test void reservedDuplicateUnknownOrExcessiveReferenceKeysAreRejected() {
        var r = ref("notes");
        for (var refs : List.of(List.of(r, r), List.of(new AssessmentReference("answer", 7, r.sourceType(), r.sourceId(), r.version(), r.text(), r.sha256(), r.normalization(), r.validity())),
                List.of(new AssessmentReference("", 7, r.sourceType(), r.sourceId(), r.version(), r.text(), r.sha256(), r.normalization(), r.validity())),
                java.util.stream.IntStream.range(0, 7).mapToObj(i -> ref("key" + i)).toList()))
            assertThatThrownBy(() -> evaluate(draft(), refs)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void protocolsDimensionsAndNullItemsAreValidatedBeforeCommit() {
        var items = new ArrayList<>(draft().items()); items.set(1, items.get(0));
        var nullItem = new ArrayList<>(draft().items()); nullItem.set(0, null);
        for (var invalid : List.of(new DetailedAssessmentDraft("future", draft().items()),
                new DetailedAssessmentDraft("six-dimensions-v1", null), new DetailedAssessmentDraft("six-dimensions-v1", List.of()),
                new DetailedAssessmentDraft("six-dimensions-v1", items), new DetailedAssessmentDraft("six-dimensions-v1", nullItem)))
            assertThatThrownBy(() -> evaluate(invalid, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidObservationStatesEmptyTextAndCitationBudgetsAreRejected() {
        var dimension = Dimension.RESPONSIBILITY;
        for (var invalid : List.of(new Item(dimension, null, "观察", ClaimStatus.SUPPORTED, "限制", false, List.of()),
                new Item(dimension, Observation.OBSERVED, "", ClaimStatus.SUPPORTED, "限制", false, List.of(answer())),
                new Item(dimension, Observation.OBSERVED, "观察", null, "限制", false, List.of(answer())),
                new Item(dimension, Observation.OBSERVED, "观察", ClaimStatus.SUPPORTED, "", false, List.of(answer())),
                new Item(dimension, Observation.OBSERVED, "观察", ClaimStatus.SUPPORTED, "限制", false, null),
                observed(dimension, false, ClaimStatus.SUPPORTED, answer(), answer(), answer(), answer()),
                observed(dimension, false, ClaimStatus.INSUFFICIENT, answer()), observed(dimension, false, ClaimStatus.SUPPORTED),
                new Item(dimension, Observation.NOT_OBSERVED, "观察", ClaimStatus.SUPPORTED, "限制", false, List.of()),
                new Item(dimension, Observation.NOT_OBSERVED, "观察", ClaimStatus.INSUFFICIENT, "限制", false, List.of(answer())),
                observed(dimension, false, ClaimStatus.SUPPORTED, new Citation("answer", 0, 7, ANSWER.substring(0, 7), null))))
            assertThatThrownBy(() -> evaluate(draft(invalid), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void correctionMustBeExplicitTechnicalAndObserved() {
        for (var item : List.of(observed(Dimension.RESPONSIBILITY, true, ClaimStatus.CONTRADICTED, answer()),
                observed(Dimension.RESPONSIBILITY, false, ClaimStatus.CONTRADICTED, answer()),
                new Item(Dimension.TECHNICAL_CORRECTNESS, Observation.NOT_OBSERVED, "无观察", ClaimStatus.INSUFFICIENT, "限制", true, List.of())))
            assertThatThrownBy(() -> evaluate(draft(item), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void identicalEvidenceAcrossDimensionsDoesNotBecomeNewFacts() {
        var result = evaluate(draft(observed(Dimension.RESPONSIBILITY, false, ClaimStatus.SUPPORTED, answer()),
                observed(Dimension.EXPRESSION, false, ClaimStatus.SUPPORTED, answer())), List.of()).detail();
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.items().get(4).claim().evidenceIds()).isEqualTo(result.items().get(5).claim().evidenceIds());
    }
    @Test void notApplicableIsNotAPoorScore() {
        var item = new Item(Dimension.TRADEOFFS, Observation.NOT_APPLICABLE, "本题不涉及方案比较", ClaimStatus.INSUFFICIENT, "不据此扣分", false, List.of());
        assertThat(evaluate(draft(item), List.of()).detail().items().get(3).status()).isEqualTo(Observation.NOT_APPLICABLE);
    }
}
