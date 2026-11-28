package cn.edu.whu.zhizhida.learning;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.common.execution.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.learning.api.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoalPlannerTest {
    final ObjectMapper json = new tools.jackson.databind.json.JsonMapper();
    final ExecutionPort.Context context = new ExecutionPort.Context("run", 7, 1);
    final Port port = new Port();
    final Journal journal = new Journal();
    final Client client = new Client();
    final GoalPlanner planner = new GoalPlanner(client, json, journal, port);

    @Test
    void searchesProposesAndRevisesUsingToolObservations() {
        client.replies.add(
                call("search_training_questions", json.createObjectNode().put("query", "事务")));
        client.replies.add(call("propose_training_plan", draft("说明事务提交失败后的处理")));
        client.replies.add(call("revise_training_plan", draft("模拟事务已提交但确认丢失，给出去重记录和恢复步骤")));
        var result = planner.plan(context, input(List.of()), () -> {});
        assertThat(result.items().getFirst().acceptance()).contains("确认丢失");
        assertThat(client.requests).hasSize(3);
        assertThat(client.requests.get(1).messages().getLast().path("content").asString())
                .contains("事务恢复", "question-1");
        assertThat(journal.attempts).isEqualTo(3);
        assertThat(journal.tools).isEqualTo(3);
        assertThat(journal.recordedUsage).hasSize(3);
    }

    @Test
    void completedCheckpointDoesNotCallModelAgain() {
        client.replies.add(
                call("search_training_questions", json.createObjectNode().put("query", "事务")));
        client.replies.add(call("propose_training_plan", draft("解释事务失败边界")));
        client.replies.add(
                json.createObjectNode()
                        .put("role", "assistant")
                        .put("content", "{\"action\":\"COMPLETE\"}"));
        var original = planner.plan(context, input(List.of()), () -> {});
        assertThat(planner.plan(context, input(List.of()), () -> {})).isEqualTo(original);
        assertThat(client.requests).hasSize(3);
    }

    @Test
    void proposalCannotEscapeAuthorizedQuestionsOrVersions() {
        var unknown = draft("验证事务边界");
        ((ObjectNode) unknown.path("items").get(0)).put("questionId", "foreign-question");
        assertThatThrownBy(() -> planner.validate(unknown, input(List.of())))
                .hasMessage("TOOL_SCOPE");
        var changed = draft("验证事务边界");
        ((ObjectNode) changed.path("items").get(0)).put("questionVersion", 9);
        assertThatThrownBy(() -> planner.validate(changed, input(List.of())))
                .hasMessage("TOOL_ARGUMENT");
    }

    @Test
    void cannotDeclareOldSupportedObservationResolvedAfterNewContradictingEvidence() {
        var old = observation("old", Instant.parse("2026-01-01T00:00:00Z"), true);
        var latest = observation("latest", Instant.parse("2026-01-02T00:00:00Z"), false);
        var proposal = draft("换场景复核职责");
        proposal.withArray("focus")
                .addObject()
                .put("dimension", "RESPONSIBILITY")
                .put("claim", "职责边界已补齐")
                .put("status", "RESOLVED")
                .putArray("observationIds")
                .add("old");
        assertThatThrownBy(() -> planner.validate(proposal, input(List.of(old, latest))))
                .hasMessage("TOOL_EVIDENCE");
        ((ObjectNode) proposal.path("focus").get(0))
                .put("status", "CONFLICTING")
                .withArray("observationIds")
                .add("latest");
        assertThat(
                        planner.validate(proposal, input(List.of(old, latest)))
                                .focus()
                                .getFirst()
                                .status())
                .isEqualTo("CONFLICTING");
    }

    @Test
    void acceptsNewSupportedEvidenceWithoutKeepingStaleDeficit() {
        var old = observation("old", Instant.parse("2026-01-01T00:00:00Z"), false);
        var latest = observation("latest", Instant.parse("2026-01-02T00:00:00Z"), true);
        var proposal = draft("验证另一场景的职责边界");
        proposal.withArray("focus")
                .addObject()
                .put("dimension", "RESPONSIBILITY")
                .put("claim", "本次已说明个人负责的事务边界")
                .put("status", "RESOLVED")
                .putArray("observationIds")
                .add("latest");
        assertThat(
                        planner.validate(proposal, input(List.of(old, latest)))
                                .focus()
                                .getFirst()
                                .status())
                .isEqualTo("RESOLVED");
    }

    @Test
    void refusesFabricatedObservationAndCompletionWithoutDraft() {
        var proposal = draft("验证事务边界");
        proposal.withArray("focus")
                .addObject()
                .put("dimension", "RESPONSIBILITY")
                .put("claim", "等待验证")
                .put("status", "UNRESOLVED")
                .putArray("observationIds")
                .add("fabricated");
        assertThatThrownBy(() -> planner.validate(proposal, input(List.of())))
                .hasMessage("TOOL_SCOPE");
        client.replies.add(
                json.createObjectNode()
                        .put("role", "assistant")
                        .put("content", "{\"action\":\"COMPLETE\"}"));
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("MODEL_GOAL_OUTPUT");
    }

    @Test
    void cancelledRunDoesNotSpendModelBudget() {
        port.active = false;
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("RUN_CANCELLED");
        assertThat(client.requests).isEmpty();
        assertThat(journal.attempts).isZero();
    }

    @Test
    void revokedHistoryStopsBeforeNextModelCall() {
        client.replies.add(
                call("search_training_questions", json.createObjectNode().put("query", "事务")));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(
                        () ->
                                planner.plan(
                                        context,
                                        input(List.of()),
                                        () -> {
                                            if (checks.incrementAndGet() > 2)
                                                throw new cn.edu.whu.zhizhida.common
                                                        .MemoryRevokedException();
                                        }))
                .isInstanceOf(cn.edu.whu.zhizhida.common.MemoryRevokedException.class);
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void failingModelCallIsCountedWithoutRuleBasedReplacement() {
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(journal.attempts).isEqualTo(1);
        assertThat(journal.failures).containsExactly("MODEL_UNAVAILABLE");
        assertThat(journal.cp.has("draft")).isFalse();
    }

    @Test
    void modelBudgetReservesCommitTimeBeforeSpendingAnAttempt() {
        journal.remaining = 5_999;
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("RUN_BUDGET");
        assertThat(journal.attempts).isZero();
        assertThat(client.requests).isEmpty();
        journal.remaining = 9_000;
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(client.requests.getFirst().timeout()).isEqualTo(java.time.Duration.ofSeconds(4));
    }

    @Test
    void deadlineReachedDuringModelCallDoesNotDispatchItsTools() {
        client.replies.add(
                call("search_training_questions", json.createObjectNode().put("query", "事务")));
        client.afterResponse = () -> journal.remaining = 0;
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("RUN_BUDGET");
        assertThat(journal.tools).isZero();
        assertThat(journal.recordedUsage).hasSize(1);
        assertThat(journal.cp.path("searched").asBoolean()).isFalse();
    }

    @Test
    void malformedArgumentsAreRejectedBeforeAnyToolRuns() {
        var batch = call("search_training_questions", json.createObjectNode().put("query", "事务"));
        var invalid = batch.withArray("tool_calls").get(0).deepCopy();
        ((ObjectNode) invalid).put("id", "invalid-second-call");
        ((ObjectNode) invalid.path("function")).put("arguments", "{\"query\":\"valid\"} {}");
        batch.withArray("tool_calls").add(invalid);
        client.replies.add(batch);
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("TOOL_ARGUMENTS");
        assertThat(journal.tools).isZero();
        assertThat(journal.cp.path("searched").asBoolean()).isFalse();
    }

    @Test
    void duplicatedCallIdsAreRejectedBeforeAnyToolRuns() {
        var batch = call("search_training_questions", json.createObjectNode().put("query", "事务"));
        batch.withArray("tool_calls").add(batch.path("tool_calls").get(0).deepCopy());
        client.replies.add(batch);
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("TOOL_CALL_ID");
        assertThat(journal.tools).isZero();
    }

    @Test
    void providerFailureKeepsItsClassifiedCodeInTheAttemptLedger() {
        client.failure = cn.edu.whu.zhizhida.common.UpstreamServiceException.coded("MODEL_NETWORK");
        assertThatThrownBy(() -> planner.plan(context, input(List.of()), () -> {}))
                .hasMessage("MODEL_NETWORK");
        assertThat(journal.failures).containsExactly("MODEL_NETWORK");
    }

    ObjectNode draft(String acceptance) {
        var root = json.createObjectNode().put("reason", "依据当前回答验证事务失败边界");
        root.putArray("items")
                .addObject()
                .put("itemKey", "verify-transaction")
                .put("questionId", "question-1")
                .put("questionVersion", 1)
                .put("title", "事务恢复")
                .put("acceptance", acceptance);
        root.putArray("focus");
        return root;
    }

    ObjectNode call(String name, JsonNode args) {
        var node = json.createObjectNode().put("role", "assistant");
        node.putArray("tool_calls")
                .addObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "function")
                .putObject("function")
                .put("name", name)
                .put("arguments", args.toString());
        return node;
    }

    GoalPlanner.Input input(List<LearningMemory.Observation> observations) {
        var goal =
                new LearningGoal(
                        "goal",
                        "事务恢复",
                        "storage",
                        "解释失败边界",
                        2,
                        List.of("question-1", "question-2"),
                        "ACTIVE",
                        1,
                        12,
                        4,
                        60,
                        30,
                        1,
                        0,
                        3,
                        3,
                        Instant.now().plusSeconds(3600),
                        List.of(),
                        1,
                        List.of("RESPONSIBILITY"),
                        true,
                        "PLANNING",
                        null);
        return new GoalPlanner.Input(
                goal,
                List.of(
                        new GoalTraining.Question("question-1", 1, "事务恢复", "storage"),
                        new GoalTraining.Question("question-2", 1, "索引选择", "storage")),
                observations,
                List.of(),
                List.of());
    }

    LearningMemory.Observation observation(String id, Instant time, boolean supported) {
        var claim =
                new AssessmentResult.Claim(
                        "claim-" + id,
                        "说明职责",
                        List.of(),
                        supported
                                ? AssessmentResult.ClaimStatus.SUPPORTED
                                : AssessmentResult.ClaimStatus.DISPUTED,
                        "test",
                        "test");
        var fact =
                new LearningMemory.Fact(
                        1,
                        "storage",
                        "RESPONSIBILITY",
                        "behavioral-six-v1",
                        "deepseek",
                        supported
                                ? AssessmentResult.Observation.OBSERVED
                                : AssessmentResult.Observation.NOT_OBSERVED,
                        claim,
                        List.of());
        return new LearningMemory.Observation(
                id,
                "session-" + id,
                "hash",
                1,
                "storage",
                "RESPONSIBILITY",
                "behavioral-six-v1",
                "deepseek",
                "ACTIVE",
                1,
                fact,
                null,
                time);
    }

    class Client implements ToolCallingChatClient {
        final Deque<JsonNode> replies = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        RuntimeException failure;
        Runnable afterResponse = () -> {};

        public Config configuration() {
            return new Config("test", "disabled", "native");
        }

        public Response complete(Request request) {
            requests.add(request);
            if (failure != null) throw failure;
            var message = replies.removeFirst();
            afterResponse.run();
            return new Response(
                    message,
                    message.has("tool_calls") ? "tool_calls" : "stop",
                    new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
        }
    }

    static class Port implements ExecutionPort {
        boolean active = true;

        public Run accept(Command command) {
            throw new UnsupportedOperationException();
        }

        public Run read(long owner, String id) {
            return new Run(id, active ? "RUNNING" : "CANCELLED", null);
        }

        public void complete(Context context, Runnable mutation) {
            mutation.run();
        }

        public void requireActive(Context context) {
            if (!active) throw new RunFailureException("RUN_CANCELLED");
        }
    }

    class Journal extends GoalPlanningStore {
        ObjectNode cp;
        int attempts, tools;
        long remaining = 60_000;
        final List<String> failures = new ArrayList<>(), recordedUsage = new ArrayList<>();

        Journal() {
            super(null, json, port);
        }

        public ObjectNode open(ExecutionPort.Context context, String config, ObjectNode initial) {
            port.requireActive(context);
            if (cp == null) cp = initial.deepCopy();
            return cp.deepCopy();
        }

        public int reserve(ExecutionPort.Context context) {
            if (attempts == 3) throw new RunFailureException("RUN_BUDGET");
            return ++attempts;
        }

        public long remainingMillis(ExecutionPort.Context context) {
            return remaining;
        }

        public void received(
                ExecutionPort.Context context,
                int attempt,
                ObjectNode state,
                String response,
                String usage) {
            cp = state.deepCopy();
            recordedUsage.add(usage);
        }

        public void save(
                ExecutionPort.Context context, ObjectNode state, int count, boolean response) {
            cp = state.deepCopy();
            tools += count;
        }

        public void failed(ExecutionPort.Context context, int attempt, String code) {
            failures.add(code);
        }
    }
}
