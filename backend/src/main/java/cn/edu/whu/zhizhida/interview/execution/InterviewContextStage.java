package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class InterviewContextStage {
        private final InterviewRunStore store;
        private final InterviewSessionRepository sessions;
        private final InterviewStateMachine machine;
        private final InterviewRunModel model;
        private final InterviewEvidenceTools tools;
        private final ObjectMapper json;
        private final InterviewSourceGuard sourceGuard;
        private final PracticeMemoryAccess memory;
        private final QuestionCatalog questions;

        InterviewContextStage(
                        InterviewRunStore store,
                        InterviewSessionRepository sessions,
                        InterviewStateMachine machine,
                        InterviewRunModel model,
                        InterviewEvidenceTools tools,
                        ObjectMapper json,
                        InterviewSourceGuard sourceGuard,
                        PracticeMemoryAccess memory,
                        QuestionCatalog questions) {
                this.store = store;
                this.sessions = sessions;
                this.machine = machine;
                this.model = model;
                this.tools = tools;
                this.json = json;
                this.sourceGuard = sourceGuard;
                this.memory = memory;
                this.questions = questions;
        }

        RunInput restoreInput(InterviewRunStore.Claim c, InterviewRunStore.Row row) {
                boolean opening = row.operation().equals("START");
                var input = json.readTree(row.input());
                var memorySelection = input.has("practiceMemory")
                                ? json.treeToValue(
                                                input.path("practiceMemory"), PracticeMemoryAccess.Selection.class)
                                : PracticeMemoryAccess.Selection.none();
                InterviewSession session = opening
                                ? json.treeToValue(input.path("session"), InterviewSession.class)
                                : sessions.findById(c.owner(), c.sessionId())
                                                .orElseThrow(() -> new RunFailureException("RUN_SESSION_GONE"));
                sourceGuard.require(c.owner(), session);

                if (opening)
                        requireOpeningQuestion(c.owner(), session);
                memory.read(c.owner(), memorySelection);
                var pending = opening ? null : pendingAnswer(session, input.path("answer"));
                return new RunInput(input, session, pending, opening, memorySelection);
        }

        private void requireOpeningQuestion(long owner, InterviewSession session) {
                // 排队中的开场尚未创建会话，须重新核对题目资格；已开始的会话继续使用固定快照。
                if (session.agent() == null
                                || session.agent().training() == null
                                || session.agent().training().question() == null)
                        return;
                var training = session.agent().training();
                var question = training.question();
                Integer expectedVersion = training.retry() == null ? question.version() : null;
                try {
                        questions.requirePublishedForExecution(owner, question.id(), expectedVersion);
                } catch (BusinessException ex) {
                        throw new SourceRevokedException();
                }
        }

        InterviewTransitions.PendingAnswer pendingAnswer(InterviewSession session, JsonNode input) {
                var answer = json.treeToValue(input, InterviewRun.Answer.class);
                var decision = new InterviewTransitions()
                                .answer(
                                                session,
                                                answer.answer(),
                                                answer.requestId(),
                                                answer.expectedTurn());
                if (!(decision instanceof InterviewTransitions.PendingAnswer pending))
                        throw new RunFailureException("RUN_TURN_CHANGED");
                return pending;
        }

        RunContext prepareContext(
                        InterviewRunStore.Claim c,
                        InterviewRunStore.Row row,
                        RunInput input,
                        BudgetProfile budget,
                        ObjectNode cp) {
                var session = input.session();
                var pending = input.pending();
                List<InterviewEvidenceTools.Section> materials = json.convertValue(
                                input.opening()
                                                ? input.payload().path("materials")
                                                : json.readTree(store.materials(c.owner(), c.sessionId())),
                                new TypeReference<>() {
                                });
                List<InterviewTurn> turns = pending == null ? session.turns() : pending.turns();
                String query = turns.stream()
                                .filter(t -> t.speaker().equals("interviewer"))
                                .reduce((a, b) -> b)
                                .map(InterviewTurn::content)
                                .orElse(session.targetRole());
                var fixedSources = row.route().equals("LEGACY")
                                ? tools.fixed(session, query)
                                : List.<KnowledgeSnapshot.Reference>of();
                String context = machine.runContext(session, pending, fixedSources);
                boolean prefetch = row.route().equals("TOOL") && needsKnowledgePrefetch(tools.knowledge(session));
                if ("KNOWLEDGE_PREFETCH".equals(cp.path("stage").asString()) && !prefetch)
                        throw new RunFailureException("RUN_CHECKPOINT_INVALID");
                var execution = new RunContext(
                                c,
                                session,
                                pending,
                                input.opening(),
                                input.memorySelection(),
                                materials,
                                turns,
                                query,
                                budget,
                                context,
                                cp);
                // 只为新任务构造初始消息；恢复任务沿用已持久化的模型响应、工具回执和修正次数。
                if (!cp.has("stage"))
                        initializeCheckpoint(execution, row.route(), fixedSources, prefetch);
                return execution;
        }

        void initializeCheckpoint(
                        RunContext execution,
                        String route,
                        List<KnowledgeSnapshot.Reference> fixedSources,
                        boolean prefetch) {
                var cp = execution.checkpoint();
                var memorySelection = execution.memorySelection();
                String stage = route.equals("FIXED") ? "FIXED" : "MODEL";
                cp.put("stage", prefetch ? "KNOWLEDGE_PREFETCH" : stage).put("repaired", false);
                cp.set("sources", json.valueToTree(fixedSources));
                var manifest = (ObjectNode) tools.manifest(
                                execution.session(), execution.turns(), execution.materials());
                if (memorySelection.available()) {
                        cp.set("practiceMemory", json.valueToTree(memorySelection));
                        manifest.set("historyCompetencies", json.valueToTree(memorySelection.competencies()));
                        manifest.put("historySamples", memorySelection.references().size())
                                        .put("historyVersion", memorySelection.version());
                }
                String phase = route.equals("TOOL") ? "ACTION" : "FINAL";
                cp.put("outputPhase", phase).put("actionRepaired", false);
                cp.set(
                                "messages",
                                json.valueToTree(
                                                model.phaseMessages(
                                                                execution.session(),
                                                                execution.pending(),
                                                                execution.context(),
                                                                manifest,
                                                                phase)));
                store.checkpoint(execution.claim(), cp.toString(), "CONTEXT", "context.ready", "面试材料已固定");
        }

        static boolean needsKnowledgePrefetch(List<KnowledgeSnapshot> selected) {
                return selected.stream().anyMatch(doc -> doc.content() != null && !doc.content().isBlank())
                                && selected.stream()
                                                .flatMap(doc -> doc.excerpts().stream())
                                                .noneMatch(
                                                                excerpt -> excerpt.content() != null
                                                                                && !excerpt.content().isBlank());
        }
}
