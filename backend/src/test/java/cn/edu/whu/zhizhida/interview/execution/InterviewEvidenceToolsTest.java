package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.knowledge.KnowledgeService;
import cn.edu.whu.zhizhida.knowledge.TrainingKnowledgeCatalog;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Reference;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Selection;
import tools.jackson.databind.json.JsonMapper;

class InterviewEvidenceToolsTest {
    final JsonMapper json = new JsonMapper();
    final KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
    final InterviewEvidenceTools tools = new InterviewEvidenceTools(json, catalog);
    final InterviewSession session = new InterviewSession("s", "后端", null, "原文\r\n项目：Redis",
            InterviewState.WAITING_ANSWER, 1, List.of());

    @org.junit.jupiter.api.BeforeEach
    void separateRegistrationMetadataFromExecutionReads() {
        // 构造时固定检索能力元数据；清除初始化调用记录，避免其干扰执行阶段的数据访问断言。
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.atLeastOnce()).retrievalProfile();
        org.mockito.Mockito.verifyNoMoreInteractions(catalog);
        org.mockito.Mockito.clearInvocations(catalog);
    }

    @Test
    void retrievalChangesInvalidateTheFrozenToolManifest() {
        when(catalog.retrievalProfile()).thenReturn("hybrid-rrf60-v1:model-v2");
        var hybrid = new InterviewEvidenceTools(json, catalog);
        assertThat(hybrid.registryManifest()).isNotEqualTo(tools.registryManifest());
        assertThat(hybrid.registryManifest().toString()).contains("hybrid-rrf60-v1:model-v2");
    }

    @Test
    void manifestBindsHashesAndReadResultsAreBoundedAndMissingIsExplicit() {
        var section = tools.sections(session).getFirst();
        assertThat(section.normalization()).isEqualTo("LF-v1");
        assertThat(section.sha256()).isEqualTo(InterviewRunStore.sha(section.content()));
        var result = tools.execute(session, List.of(), "read_material_section",
                json.readTree("{\"sectionId\":\"resume-0\",\"maxChars\":2}")).payload();
        assertThat(result.path("status").asText()).isEqualTo("FOUND");
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("content").asText()).isEqualTo("原文");
        assertThat(tools.execute(session, List.of(), "read_material_section",
                json.readTree("{\"sectionId\":\"foreign\",\"maxChars\":2}")).payload().path("status").asText())
                .isEqualTo("MISSING");
        verifyNoInteractions(catalog);
    }

    @Test
    void materialSectionsKeepUtf16OffsetsWithoutSplittingAnEmojiAtTheBoundary() {
        String raw = "a".repeat(1999) + "😀" + "b\r\n" + "c".repeat(2000) + "😀";
        String normalized = raw.replace("\r\n", "\n");
        var sections = new ArrayList<InterviewEvidenceTools.Section>();
        InterviewEvidenceTools.material(sections, "resume", raw, "revision-7", false);

        assertThat(sections.getFirst().end()).isEqualTo(1999);
        assertThat(sections.get(1).start()).isEqualTo(1999);
        assertThat(sections.get(1).sectionId()).isEqualTo("resume-1999");
        assertThat(sections.get(1).content()).startsWith("😀b\n");
        var assembled = new StringBuilder();
        int next = 0;
        for (var section : sections) {
            assertThat(section.start()).isEqualTo(next);
            assertThat(section.content()).isEqualTo(normalized.substring(section.start(), section.end()));
            assertThat(section.content().length()).isLessThanOrEqualTo(2000);
            assertThat(section.normalization()).isEqualTo("LF-v1");
            assertThat(section.sha256()).isEqualTo(InterviewRunStore.sha(section.content()));
            assertValidUtf16(section.content());
            var restored = json.readTree(new String(json.writeValueAsBytes(section), StandardCharsets.UTF_8));
            assertThat(restored.path("content").asText()).isEqualTo(section.content());
            assembled.append(section.content());
            next = section.end();
        }
        assertThat(assembled.toString()).isEqualTo(normalized);
        assertThat(next).isEqualTo(normalized.length());
    }

    @ParameterizedTest
    @CsvSource({ "read_material_section, sectionId, resume-0", "read_answer_evidence, answerId, answer-1" })
    void readPrefixesAvoidSplittingEmojiAndRetainFullSourceHashes(String name, String idField, String id) {
        String raw = "a😀b\r\n尾部";
        var scoped = new InterviewSession("s", "后端", null, raw, InterviewState.WAITING_ANSWER, 1, List.of());
        var turns = List.of(new InterviewTurn("user", raw));
        String source = name.equals("read_material_section") ? raw.replace("\r\n", "\n") : raw;
        var result = tools.execute(scoped, turns, name,
                json.createObjectNode().put(idField, id).put("maxChars", 2)).payload();
        assertThat(result.path("status").asText()).isEqualTo("FOUND");
        assertThat(result.path("content").asText()).isEqualTo("a");
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("sha256").asText()).isEqualTo(InterviewRunStore.sha(source));
        var full = tools.execute(scoped, turns, name,
                json.createObjectNode().put(idField, id).put("maxChars", 2000)).payload();
        assertThat(full.path("content").asText()).isEqualTo(source);
        assertThat(full.path("truncated").asBoolean()).isFalse();
        assertThat(source.substring(1, 3)).isEqualTo("😀");
        assertValidUtf16(result.path("content").asText());
    }

    @Test
    void budgetOfOneCannotReturnHalfOfALeadingEmoji() {
        var result = tools.execute(session, List.of(new InterviewTurn("user", "😀回答")), "read_answer_evidence",
                json.readTree("{\"answerId\":\"answer-1\",\"maxChars\":1}")).payload();
        assertThat(result.path("status").asText()).isEqualTo("FOUND");
        assertThat(result.path("content").asText()).isEmpty();
        assertThat(result.path("truncated").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"query\":\"Redis\",\"topK\":7}", "{\"query\":\"Redis\",\"topK\":0}", "{\"query\":\"\",\"topK\":1}",
            "{\"query\":\"Redis\",\"topK\":1,\"userId\":7}", "{\"query\":\"Redis\",\"topK\":1.5}" })
    void rejectsOutOfScopeArgumentsBeforeReading(String args) {
        assertThatThrownBy(() -> tools.execute(session, List.of(), "search_knowledge", json.readTree(args)))
                .hasMessage("TOOL_ARGUMENTS");
        verifyNoInteractions(catalog);
    }

    @Test
    void currentAnswerIdsCannotReadAnotherSession() {
        var turns = List.of(new InterviewTurn("user", "本场待评审回答", "r1"));
        assertThat(tools
                .execute(session, turns, "read_answer_evidence",
                        json.readTree("{\"answerId\":\"answer-1\",\"maxChars\":2000}"))
                .payload().path("content").asText()).isEqualTo("本场待评审回答");
        assertThat(tools
                .execute(session, turns, "read_answer_evidence",
                        json.readTree("{\"answerId\":\"other-session-answer\",\"maxChars\":2000}"))
                .payload().path("status").asText()).isEqualTo("MISSING");
    }

    @Test
    void emptySearchAndUnknownToolHaveDifferentOutcomes() {
        when(catalog.rank(List.of(), "Redis")).thenReturn(List.of());
        assertThat(
                tools.execute(session, List.of(), "search_knowledge", json.readTree("{\"query\":\"Redis\",\"topK\":1}"))
                        .payload().path("status").asText())
                .isEqualTo("EMPTY");
        assertThatThrownBy(() -> tools.validate("shell", json.readTree("{}"))).hasMessage("TOOL_DENIED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "[]", "{\"sectionId\":42,\"maxChars\":1}",
            "{\"sectionId\":\"\",\"maxChars\":1}", "{\"sectionId\":\"resume-0\",\"maxChars\":0}",
            "{\"sectionId\":\"resume-0\",\"maxChars\":2001}", "{\"sectionId\":\"resume-0\",\"maxChars\":1.5}",
            "{\"sectionId\":\"resume-0\",\"maxChars\":92233720368547758080}" })
    void malformedMaterialParametersCannotExecute(String args) {
        assertThatThrownBy(() -> tools.execute(session, List.of(), "read_material_section", json.readTree(args)))
                .hasMessage("TOOL_ARGUMENTS");
        assertThatThrownBy(() -> tools.validate("read_material_section", null)).hasMessage("TOOL_ARGUMENTS");
        verifyNoInteractions(catalog);
    }

    @Test
    void knowledgeToolAppliesTopKAcrossDocumentsBeforeGrouping() {
        var first = new KnowledgeSnapshot("a", "第一份", "a.md", 1, "hash-a", "redis mysql", List.of());
        var second = new KnowledgeSnapshot("b", "第二份", "b.md", 2, "hash-b", "redis mysql postgresql\n\nredis", List.of());
        var state = new InterviewAgentState("fixture", "mock", List.of(), "storage", List.of(), List.of(), List.of(),
                new InterviewTraining(null, null, null, List.of(first, second)));
        var scoped = session.withAgent(state);
        var realTools = new InterviewEvidenceTools(json, new TrainingKnowledgeCatalog(mock(KnowledgeService.class)));
        var args = json.createObjectNode().put("query", "redis mysql postgresql").put("topK", 1);

        var best = realTools.execute(scoped, List.of(), "search_knowledge", args);
        assertThat(best.references()).extracting(KnowledgeSnapshot.Reference::id).containsExactly("b");
        assertThat(best.references().getFirst().sha256()).isEqualTo("hash-b");
        assertThat(best.references().getFirst().excerpts()).extracting(KnowledgeSnapshot.Excerpt::chunkId)
                .containsExactly("b:v2:1");

        var bestTwo = realTools.execute(scoped, List.of(), "search_knowledge", args.put("topK", 2));
        assertThat(bestTwo.references()).extracting(KnowledgeSnapshot.Reference::id).containsExactly("b", "a");
        assertThat(bestTwo.references()).allSatisfy(ref -> assertThat(ref.excerpts()).hasSize(1));
        assertThat(bestTwo.payload().path("truncated").asBoolean()).isTrue();
    }

    @Test
    void resultBudgetBoundsWholeBatchOfKnowledgeAndPreservesProvenance() {
        var first = new KnowledgeSnapshot("doc", "资料", "fixture", 3, "hash", "archived", List.of());
        var second = new KnowledgeSnapshot("other", "其他", "fixture", 2, "other-hash", "archived", List.of());
        var docs = List.of(first, second);
        var state = new InterviewAgentState("fixture", "mock", List.of(), "storage", List.of(), List.of(), List.of(),
                new InterviewTraining(null, null, null, docs));
        var scoped = session.withAgent(state);
        when(catalog.rank(docs, "数据库")).thenReturn(List.of(
                new KnowledgeCatalog.RankedExcerpt(first, new KnowledgeSnapshot.Excerpt("a", "heading", 1, 2, "a".repeat(3500))),
                new KnowledgeCatalog.RankedExcerpt(second, new KnowledgeSnapshot.Excerpt("b", "heading", 3, 4, "b".repeat(3500))),
                new KnowledgeCatalog.RankedExcerpt(first, new KnowledgeSnapshot.Excerpt("c", "heading", 5, 6, "c"))));
        var result = tools.execute(scoped, List.of(), "search_knowledge",
                json.readTree("{\"query\":\"数据库\",\"topK\":6}"));
        assertThat(result.payload().path("truncated").asBoolean()).isTrue();
        assertThat(result.references().getFirst().version()).isEqualTo(3);
        assertThat(result.references()).extracting(KnowledgeSnapshot.Reference::id).containsExactly("doc", "other");
        assertThat(result.references().getFirst().excerpts()).extracting(KnowledgeSnapshot.Excerpt::chunkId).containsExactly("a");
        assertThat(result.references().getLast().excerpts().getFirst().content()).isEqualTo("b".repeat(2500));
        result = tools.execute(scoped, List.of(), "search_knowledge", json.readTree("{\"query\":\"数据库\",\"topK\":1}"));
        assertThat(result.references().getFirst().excerpts()).hasSize(1);
    }

    @Test
    void remainingKnowledgeBudgetPreservesEmojiAndOmitsEmptyPrefixes() {
        var excerpts = List.of(new KnowledgeSnapshot.Excerpt("a", "heading", 1, 2, "a".repeat(3500)),
                new KnowledgeSnapshot.Excerpt("b", "heading", 3, 4, "b".repeat(2499) + "😀tail"),
                new KnowledgeSnapshot.Excerpt("c", "heading", 5, 6, "😀"),
                new KnowledgeSnapshot.Excerpt("d", "heading", 7, 8, "z"));
        var source = new KnowledgeSnapshot("doc", "资料", "fixture", 3, "full-document-hash", "archived", List.of());
        when(catalog.rank(List.of(), "emoji")).thenReturn(excerpts.stream()
                .map(excerpt -> new KnowledgeCatalog.RankedExcerpt(source, excerpt)).toList());
        var result = tools.execute(session, List.of(), "search_knowledge",
                json.readTree("{\"query\":\"emoji\",\"topK\":6}"));
        assertThat(result.payload().path("status").asText()).isEqualTo("FOUND");
        assertThat(result.payload().path("truncated").asBoolean()).isTrue();
        var reference = result.references().getFirst();
        assertThat(reference.version()).isEqualTo(3);
        assertThat(reference.sha256()).isEqualTo("full-document-hash");
        assertThat(reference.excerpts()).extracting(KnowledgeSnapshot.Excerpt::chunkId).containsExactly("a", "b", "d");
        assertThat(reference.excerpts().get(1).content()).isEqualTo("b".repeat(2499));
        assertThat(reference.excerpts().get(1).startLine()).isEqualTo(3);
        assertThat(reference.excerpts().get(1).endLine()).isEqualTo(4);
        assertThat(reference.excerpts().stream().mapToInt(e -> e.content().length()).sum()).isEqualTo(6000);
        reference.excerpts().forEach(e -> assertValidUtf16(e.content()));
        var restored = json.readTree(new String(json.writeValueAsBytes(result.payload()), StandardCharsets.UTF_8));
        assertThat(restored).isEqualTo(result.payload());
    }

    @Test
    void manifestHidesTextAndAdditionalProjectSectionsRemainReadable() {
        var additional = new ArrayList<InterviewEvidenceTools.Section>();
        InterviewEvidenceTools.material(additional, "project-1", "独立项目正文\r\n尾部", "revision-9", true);
        var turns = List.of(new InterviewTurn("interviewer", "问题"), new InterviewTurn("user", "回答一"),
                new InterviewTurn("system", "反馈"), new InterviewTurn("user", "回答二"));
        var manifest = tools.manifest(session, turns, additional);
        assertThat(manifest.toString()).doesNotContain("独立项目正文", "回答一");
        assertThat(manifest.path("answers")).hasSize(2);
        var result = tools.execute(session, turns, "read_material_section",
                json.readTree("{\"sectionId\":\"project-1-0\",\"maxChars\":2000}"), additional);
        assertThat(result.payload().path("content").asText()).contains("独立项目正文\n尾部");
        assertThat(result.payload().path("sourceVersion").asText()).isEqualTo("revision-9");
        assertThat(tools
                .execute(session, turns, "read_answer_evidence",
                        json.readTree("{\"answerId\":\"answer-2\",\"maxChars\":2000}"))
                .payload().path("content").asText()).isEqualTo("回答二");
        assertThatThrownBy(() -> tools.validate("search_knowledge",
                json.createObjectNode().put("query", "x".repeat(501)).put("topK", 1))).hasMessage("TOOL_ARGUMENTS");
    }

    @ParameterizedTest
    @CsvSource({
            "read_material_section, sectionId, maxChars, 80, 2000",
            "read_answer_evidence, answerId, maxChars, 80, 2000",
            "search_knowledge, query, topK, 500, 6",
            "find_practice_evidence, competencyId, limit, 80, 5",
            "get_learning_progress, competencyId, limit, 80, 5" })
    void declaredContractsAndServerChecksAgreeAtBoundaries(String name, String text, String number,
            int maxText, int maxNumber) {
        String longest = "x".repeat(maxText);
        var memory = selection(List.of("x", longest, longest + "x", " "));
        var definition = tools.definitions(memory).stream()
                .filter(tool -> tool.path("function").path("name").asText().equals(name))
                .findFirst().orElseThrow().path("function");
        assertThat(definition.path("description").asText()).isNotBlank();
        assertThat(definition.path("parameters")).isEqualTo(json.readTree("""
                {"type":"object","additionalProperties":false,
                 "properties":{"%s":{"type":"string","minLength":1,"maxLength":%d},
                               "%s":{"type":"integer","minimum":1,"maximum":%d}},
                 "required":["%s","%s"]}
                """.formatted(text, maxText, number, maxNumber, text, number)));
        assertThatCode(() -> tools.validate(name,
                json.createObjectNode().put(text, "x").put(number, 1), memory)).doesNotThrowAnyException();
        assertThatCode(() -> tools.validate(name,
                json.createObjectNode().put(text, longest).put(number, maxNumber), memory)).doesNotThrowAnyException();

        var invalid = List.of(
                json.createObjectNode().put(text, "x"),
                json.createObjectNode().put(number, 1),
                json.createObjectNode().put(text, "x").put(number, 1).put("ownerId", 2),
                json.createObjectNode().put(text, " ").put(number, 1),
                json.createObjectNode().put(text, "").put(number, 1),
                json.createObjectNode().put(text, longest + "x").put(number, 1),
                json.createObjectNode().put(text, 1).put(number, 1),
                json.createObjectNode().put(text, "x").put(number, 0),
                json.createObjectNode().put(text, "x").put(number, maxNumber + 1),
                json.createObjectNode().put(text, "x").put(number, 1.5),
                json.createObjectNode().put(text, "x").put(number, Long.MAX_VALUE));
        for (var args : invalid) {
            assertThatThrownBy(() -> tools.validate(name, args, memory))
                    .isInstanceOfSatisfying(RunFailureException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TOOL_ARGUMENTS"));
        }
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest
    @ValueSource(strings = { "find_practice_evidence", "get_learning_progress" })
    void historyRequiresAnAvailableSelectionAndAuthorizedCompetency(String name) {
        var args = json.createObjectNode().put("competencyId", "foreign").put("limit", 1);
        assertThatThrownBy(() -> tools.validate(name, args, Selection.none())).hasMessage("TOOL_DENIED");
        assertThatThrownBy(() -> tools.validate(name, args, selection(List.of("storage"))))
                .hasMessage("TOOL_SCOPE");
        assertThatThrownBy(() -> tools.validate(name, args)).hasMessage("TOOL_DENIED");
        verifyNoInteractions(catalog);
    }

    @Test
    void declarationOrderIsStableAndUnavailableHistoryIsNotExposed() {
        assertThat(tools.definitions(Selection.none()).stream()
                .map(tool -> tool.path("function").path("name").asText()))
                .containsExactly("read_material_section", "read_answer_evidence", "search_knowledge");
        assertThat(tools.definitions(selection(List.of("storage"))).stream()
                .map(tool -> tool.path("function").path("name").asText()))
                .containsExactly("read_material_section", "read_answer_evidence", "search_knowledge",
                        "find_practice_evidence", "get_learning_progress");
        assertThat(tools.allDefinitions()).isEqualTo(tools.definitions(selection(List.of("storage"))));
        assertThatThrownBy(() -> tools.validate(null, json.createObjectNode())).hasMessage("TOOL_DENIED");
    }

    private static void assertValidUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertThat(i + 1).isLessThan(text.length());
                assertThat(Character.isLowSurrogate(text.charAt(++i))).isTrue();
            } else {
                assertThat(Character.isLowSurrogate(current)).isFalse();
            }
        }
    }

    private Selection selection(List<String> competencies) {
        return new Selection("practice-memory-v1", "PRACTICE", Instant.EPOCH, competencies,
                List.of(new Reference("observation", 1, "session", "version")));
    }
}
