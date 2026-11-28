package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.interview.InterviewReviewRejected;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.api.QuestionSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceReviewTest {
    private final JsonMapper json = new JsonMapper();
    private static final String QUOTE = "提交后确认";
    private static final Map<String, EvidenceReview.Source> REFERENCES =
            Map.of(
                    "knowledge/note/chunk-1",
                    new EvidenceReview.Source("提交后确认，重复投递通过业务键幂等处理。", true));

    private ObjectNode action(String action, String status, String source) {
        var result = json.createObjectNode().put("action", action);
        var check =
                result.putArray("checks")
                        .addObject()
                        .put("dimension", "TECHNICAL_CORRECTNESS")
                        .put("claim", "提交数据库后再确认消息可以避免数据丢失")
                        .put("status", status)
                        .put("nextAction", "检索确认丢失后的重复投递行为");
        var evidence = check.putArray("evidence");
        if (source != null) evidence.addObject().put("sourceId", source).put("quote", "提交后确认");
        return result;
    }

    @Test
    void newEvidenceCanChangeAClaimAfterOnePersistentRevision() {
        var cp = json.createObjectNode();
        var unresolved = action("REVISE", "INSUFFICIENT", null);
        EvidenceReview.validate(unresolved, Map.of(), false);
        assertThat(EvidenceReview.apply(cp, unresolved)).isTrue();
        cp = (ObjectNode) json.readTree(cp.toString());
        var supported = action("COMPLETE", "SUPPORTED", "knowledge/note/chunk-1");
        EvidenceReview.validate(supported, REFERENCES, false);
        assertThat(EvidenceReview.apply(cp, supported)).isFalse();
        assertThat(cp.path("evidenceHistory")).hasSize(2);
        assertThat(cp.path("evidenceReview").get(0).path("status").asString())
                .isEqualTo("SUPPORTED");
        var saved = cp;
        assertThatThrownBy(() -> EvidenceReview.apply(saved, unresolved))
                .isInstanceOf(InterviewReviewRejected.class);
        assertThat(saved.path("evidenceReview").get(0).path("status").asString())
                .isEqualTo("SUPPORTED");
    }

    @Test
    void unsupportedOrFabricatedSourcesAreRejected() {
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validate(
                                        action("COMPLETE", "SUPPORTED", null), Map.of(), false))
                .isInstanceOf(InterviewReviewRejected.class);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validate(
                                        action("COMPLETE", "SUPPORTED", "answer-2"),
                                        Map.of("answer-1", new EvidenceReview.Source(QUOTE, false)),
                                        false))
                .isInstanceOf(InterviewReviewRejected.class);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validate(
                                        action("COMPLETE", "SUPPORTED", "answer-1"),
                                        Map.of(
                                                "answer-1",
                                                new EvidenceReview.Source("先确认再提交", false)),
                                        false))
                .isInstanceOf(InterviewReviewRejected.class);
        var empty = json.createObjectNode();
        empty.putArray("checks");
        assertThatThrownBy(() -> EvidenceReview.validate(empty, Map.of(), false))
                .isInstanceOf(InterviewReviewRejected.class);
    }

    @Test
    void finalCannotPromoteUnknownOrMakeUnreviewedMajorCorrections() {
        var result =
                json.readTree(
                        """
                {"assessment":{"detail":{"items":[{"dimension":"TECHNICAL_CORRECTNESS","claimStatus":"SUPPORTED","majorCorrection":false}]}}}
                """);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        result,
                                        action("COMPLETE", "INSUFFICIENT", null).path("checks"),
                                        REFERENCES))
                .isInstanceOf(InterviewReviewRejected.class);
        ((ObjectNode) result.path("assessment").path("detail").path("items").get(0))
                .put("majorCorrection", true)
                .put("claimStatus", "CONTRADICTED");
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        result, json.createArrayNode(), REFERENCES))
                .isInstanceOf(InterviewReviewRejected.class);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        result,
                                        action("COMPLETE", "CONTRADICTED", "answer-1")
                                                .path("checks"),
                                        REFERENCES))
                .isInstanceOf(InterviewReviewRejected.class);
        assertThatCode(
                        () ->
                                EvidenceReview.validateFinal(
                                        result,
                                        action("COMPLETE", "CONTRADICTED", "knowledge/note/chunk-1")
                                                .path("checks"),
                                        REFERENCES))
                .doesNotThrowAnyException();
    }

    @Test
    void initialQuestionAndKnowledgeUseTheSameKeysAsFinalAssessmentWithoutToolCalls() {
        var question = question(QUOTE);
        var excerpt = new KnowledgeSnapshot.Excerpt("chunk-1", "确认时机", 1, 1, QUOTE);
        var doc =
                new KnowledgeSnapshot(
                        "note",
                        "消息确认",
                        "资料",
                        1,
                        InterviewRunStore.sha(QUOTE),
                        QUOTE,
                        List.of(excerpt));
        var training = new InterviewTraining(null, null, question, List.of(doc));
        var sources = EvidenceReview.references(1, training, List.of());
        assertThat(sources.keySet()).containsExactly("question/q1", "knowledge/note/chunk-1");
        for (var source : sources.keySet()) {
            var action = action("COMPLETE", "CONTRADICTED", source);
            EvidenceReview.validate(action, sources, false);
            EvidenceReview.validateFinal(
                    finalAssessment("CONTRADICTED", true), action.path("checks"), sources);
        }
    }

    @Test
    void referenceCatalogKeepsFinalSizeLimitsAndDoesNotAuthorizeOmittedText() {
        var training = new InterviewTraining(null, null, question(QUOTE.repeat(501)), List.of());
        var sources = EvidenceReview.references(1, training, List.of());
        assertThat(sources).isEmpty();
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validate(
                                        action("COMPLETE", "CONTRADICTED", "question/q1"),
                                        sources,
                                        false))
                .isInstanceOf(InterviewReviewRejected.class);
        var excerpt = new KnowledgeSnapshot.Excerpt("found", "确认", 1, 1, QUOTE);
        var retrieved = new KnowledgeSnapshot.Reference("note", "资料", 2, "hash", List.of(excerpt));
        var retrievedSources = EvidenceReview.references(1, training, List.of(retrieved));
        assertThat(retrievedSources.keySet()).containsExactly("knowledge/note/found");
        EvidenceReview.validate(
                action("COMPLETE", "SUPPORTED", "knowledge/note/found"), retrievedSources, false);
    }

    @Test
    void referenceAuthorityComesFromCatalogAndNotSlashesInAnIdentifier() {
        var selfReport = Map.of("resume/project", new EvidenceReview.Source(QUOTE, false));
        var check = action("COMPLETE", "CONTRADICTED", "resume/project");
        EvidenceReview.validate(check, selfReport, false);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        finalAssessment("CONTRADICTED", true),
                                        check.path("checks"),
                                        selfReport))
                .isInstanceOf(InterviewReviewRejected.class);
        var external = Map.of("reference", new EvidenceReview.Source(QUOTE, true));
        EvidenceReview.validateFinal(
                finalAssessment("CONTRADICTED", true),
                action("COMPLETE", "CONTRADICTED", "reference").path("checks"),
                external);
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        finalAssessment("CONTRADICTED", true),
                                        check.path("checks"),
                                        Map.of()))
                .isInstanceOf(InterviewReviewRejected.class);
    }

    @Test
    void legacyItemsCannotBypassUnresolvedChecksEvenWhenDetailPreservesUnknown() {
        var checks = action("COMPLETE", "INSUFFICIENT", null).path("checks");
        ((ObjectNode) checks.get(0)).put("dimension", "CAUSAL_EXPLANATION");
        var payload = json.createObjectNode();
        var assessment = payload.putObject("assessment");
        var legacy =
                assessment
                        .putArray("items")
                        .addObject()
                        .put("dimension", "CAUSAL_EXPLANATION")
                        .put("claimStatus", "SUPPORTED");
        assertThatThrownBy(() -> EvidenceReview.validateFinal(payload, checks, Map.of()))
                .isInstanceOf(InterviewReviewRejected.class);
        assessment.set(
                "detail", finalAssessment("INSUFFICIENT", false).path("assessment").path("detail"));
        ((ObjectNode) assessment.path("detail").path("items").get(0))
                .put("dimension", "CAUSAL_EXPLANATION");
        assertThatThrownBy(() -> EvidenceReview.validateFinal(payload, checks, Map.of()))
                .isInstanceOf(InterviewReviewRejected.class);
        legacy.put("claimStatus", "INSUFFICIENT");
        EvidenceReview.validateFinal(payload, checks, Map.of());
    }

    @Test
    void finalCannotReverseSupportOrContradictionButMayKeepUncertainty() {
        for (var checked : List.of("SUPPORTED", "CONTRADICTED")) {
            var checks = action("COMPLETE", checked, "knowledge/note/chunk-1").path("checks");
            String opposite = checked.equals("SUPPORTED") ? "CONTRADICTED" : "SUPPORTED";
            assertThatThrownBy(
                            () ->
                                    EvidenceReview.validateFinal(
                                            finalAssessment(opposite, false), checks, REFERENCES))
                    .isInstanceOf(InterviewReviewRejected.class);
            for (var uncertain : List.of("INSUFFICIENT", "DISPUTED"))
                EvidenceReview.validateFinal(finalAssessment(uncertain, false), checks, REFERENCES);
        }
        var otherDimension = action("COMPLETE", "CONTRADICTED", "knowledge/note/chunk-1");
        ((ObjectNode) otherDimension.path("checks").get(0)).put("dimension", "EXPRESSION");
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        finalAssessment("CONTRADICTED", true),
                                        otherDimension.path("checks"),
                                        REFERENCES))
                .isInstanceOf(InterviewReviewRejected.class);
    }

    @Test
    void openingHasNoAssessmentToGate() {
        EvidenceReview.validateFinal(
                json.createObjectNode().put("question", "如何处理重复投递？"),
                json.createArrayNode(),
                Map.of());
    }

    @Test
    void uncheckedDimensionsCannotBorrowAnotherDimensionsCheck() {
        var action = action("COMPLETE", "SUPPORTED", "knowledge/note/chunk-1");
        ((ObjectNode) action.path("checks").get(0)).put("dimension", "EXPRESSION");
        for (var checks : List.of(action.path("checks"), json.createArrayNode())) {
            for (var conclusion : List.of("SUPPORTED", "CONTRADICTED"))
                assertThatThrownBy(
                                () ->
                                        EvidenceReview.validateFinal(
                                                finalAssessment(conclusion, false),
                                                checks,
                                                REFERENCES))
                        .isInstanceOf(InterviewReviewRejected.class);
            for (var conclusion : List.of("INSUFFICIENT", "DISPUTED"))
                EvidenceReview.validateFinal(
                        finalAssessment(conclusion, false), checks, REFERENCES);
        }
        var legacy = json.createObjectNode();
        legacy.putObject("assessment")
                .putArray("items")
                .addObject()
                .put("dimension", "CAUSAL_EXPLANATION")
                .put("claimStatus", "SUPPORTED");
        assertThatThrownBy(
                        () ->
                                EvidenceReview.validateFinal(
                                        legacy, action.path("checks"), REFERENCES))
                .isInstanceOf(InterviewReviewRejected.class);
    }

    private JsonNode finalAssessment(String status, boolean correction) {
        var payload = json.createObjectNode();
        payload.putObject("assessment")
                .putObject("detail")
                .putArray("items")
                .addObject()
                .put("dimension", "TECHNICAL_CORRECTNESS")
                .put("claimStatus", status)
                .put("majorCorrection", correction);
        return payload;
    }

    private QuestionSnapshot question(String reference) {
        return new QuestionSnapshot(
                "q1",
                1,
                "消息确认",
                "何时确认？",
                "DATABASE",
                "storage",
                InterviewDifficulty.STANDARD,
                List.of(),
                List.of(),
                reference,
                List.of(),
                List.of(),
                "CURATED",
                "资料",
                null);
    }
}
