package cn.edu.whu.zhizhida.interview;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.edu.whu.zhizhida.ai.api.ChatInput;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentReference;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 面试模型的纯提示词构造逻辑。 */
public final class InterviewPromptBuilder {
        private static String normalizeNullable(String value) {
                return value == null ? "" : value.trim();
        }

        private final ObjectMapper objectMapper;
        private final InterviewPromptTemplates templates;

        public InterviewPromptBuilder(ObjectMapper objectMapper) {
                this(objectMapper, InterviewPromptTemplates.defaults());
        }

        InterviewPromptBuilder(ObjectMapper objectMapper, InterviewPromptTemplates templates) {
                this.objectMapper = Objects.requireNonNull(objectMapper);
                this.templates = Objects.requireNonNull(templates);
        }

        /** context 兼容普通材料文本和 InterviewAgentContext.encode 的带前缀 JSON 封装。 */
        public ChatInput openingInput(String targetRole, String context, String reason) {
                ObjectNode input = objectMapper.createObjectNode();
                input.put("targetRole", targetRole.trim());
                var agent = putContext(input, context, InterviewAgentContext.decode(context));
                putRepairFeedback(input, reason);
                return new ChatInput(
                                templates.get("opening")
                                                + (agent ? templates.get("agent") : "")
                                                + ("adaptive-v1"
                                                                .equals(
                                                                                input.path("agentRouting")
                                                                                                .path("strategy")
                                                                                                .asString())
                                                                                                                ? templates.get("adaptiveRouting")
                                                                                                                : "")
                                                + (input.path("untrustedEvidence").has("knowledgeReferences")
                                                                ? templates.get("knowledgeOpening")
                                                                : "")
                                                + InterviewOutputContract.prompt(true, agent, false, planning(input))
                                                + (reason == null ? "" : templates.get("repair")),
                                agent ? boundedInput(input) : input.toString(),
                                0.55,
                                600);
        }

        public ChatInput reviewInput(
                        String targetRole,
                        String context,
                        List<InterviewTurn> transcript,
                        int answeredCount,
                        boolean finalRound,
                        String reason) {
                var agentContext = InterviewAgentContext.decode(context);
                var references = agentContext == null || agentContext.checkpoint() == null
                                ? List.<AssessmentReference>of()
                                : InterviewAssessmentSources.references(0, agentContext.checkpoint().training());
                return reviewInput(
                                targetRole,
                                context,
                                agentContext,
                                transcript,
                                answeredCount,
                                finalRound,
                                reason,
                                references);
        }

        ChatInput reviewInput(
                        String targetRole,
                        String context,
                        List<InterviewTurn> transcript,
                        int answeredCount,
                        boolean finalRound,
                        String reason,
                        List<AssessmentReference> references) {
                return reviewInput(targetRole, context, InterviewAgentContext.decode(context), transcript,
                                answeredCount, finalRound, reason, references);
        }

        private ChatInput reviewInput(
                        String targetRole,
                        String context,
                        InterviewAgentContext agentContext,
                        List<InterviewTurn> transcript,
                        int answeredCount,
                        boolean finalRound,
                        String reason,
                        List<AssessmentReference> references) {
                ObjectNode input = objectMapper.createObjectNode();
                input.put("targetRole", targetRole);
                var agent = putContext(input, context, agentContext);
                input.put("answeredCount", answeredCount);
                input.put("finalRound", finalRound);
                input.set("transcript", objectMapper.valueToTree(transcript));
                input.put("citationProtocol", InterviewCitationAnchors.VERSION);
                input.set(
                                "citationAnchors",
                                objectMapper.valueToTree(InterviewCitationAnchors.catalog(transcript, references)));
                for (var anchor : input.path("citationAnchors")) {
                        // 最新回答已完整保留在 transcript；避免最坏 JSON 转义时复制正文耗尽输入预算。
                        if (anchor.path("sourceKey").asString().equals("answer")) {
                                ((ObjectNode) anchor).remove("content");
                                ((ObjectNode) anchor).put("contentPath", "transcript 中最新 user 的完整 content");
                        }
                }
                if (agent) {
                        var catalog = ((ObjectNode) input.path("untrustedEvidence")).putArray("assessmentSources");
                        for (var source : references)
                                catalog.addObject()
                                                .put("sourceKey", source.key())
                                                .put("sourceType", source.sourceType())
                                                .put("sourceVersion", source.version())
                                                .put("sha256", source.sha256())
                                                .put("content", source.text());
                }
                putRepairFeedback(input, reason);
                return new ChatInput(
                                templates.get("review")
                                                + (agent ? templates.get("agent") + templates.get("assessment") : "")
                                                + ("adaptive-v1"
                                                                .equals(
                                                                                input.path("agentRouting")
                                                                                                .path("strategy")
                                                                                                .asString())
                                                                                                                ? templates.get("adaptiveRouting")
                                                                                                                : "")
                                                + (agent
                                                                ? templates.get("routing").formatted(
                                                                                input.path("agentRouting")
                                                                                                .path("allowedActions"),
                                                                                input.path("agentRouting")
                                                                                                .path("allowedProbes"))
                                                                : "")
                                                + InterviewOutputContract.prompt(false, agent, finalRound,
                                                                planning(input))
                                                + (reason == null ? "" : templates.get("repair")),
                                agent ? boundedInput(input) : input.toString(),
                                0.35,
                                agent ? 2000 : 1400);
        }

        private boolean planning(JsonNode input) {
                return "interview-agent-v6".equals(input.path("agentCheckpoint").path("version").asString());
        }

        private void putRepairFeedback(ObjectNode input, String reason) {
                if (reason == null)
                        return;
                // 保留原字符串字段供旧调用方读取；新调用方通过固定 code/path/constraint 消费诊断。
                input.put("validationFailure", reason.substring(0, Math.min(600, reason.length())));
                try {
                        var feedback = objectMapper.readTree(reason);
                        if (feedback != null
                                        && feedback.isObject()
                                        && feedback.path("code").isString()
                                        && feedback.path("path").isString()
                                        && feedback.path("constraint").isString())
                                input.set("validationFeedback", feedback);
                } catch (JacksonException ignored) {
                        /* 兼容旧版纯文本原因。 */
                }
        }

        /** 只含稳定模板和常量；用户材料、回答和实际请求不得进入此清单。 */
        public JsonNode templateManifest() {
                var manifest = objectMapper
                                .createObjectNode()
                                .put("templateVersion", templates.version())
                                .put("outputContractVersion", InterviewOutputContract.VERSION)
                                .put("citationProtocol", InterviewCitationAnchors.VERSION);
                manifest.set(
                                "citationPolicy", objectMapper.valueToTree(InterviewCitationAnchors.manifest()));
                var prompts = manifest.putObject("templates");
                prompts.put("opening", templates.get("opening"))
                                .put("review", templates.get("review"))
                                .put("agent", templates.get("agent"))
                                .put("assessment", templates.get("assessment"))
                                .put("repair", templates.get("repair"))
                                .put("knowledgeOpening", templates.get("knowledgeOpening"))
                                .put("routing", templates.get("routing"))
                                .put("adaptiveRouting", templates.get("adaptiveRouting"));
                var contracts = manifest.putArray("contracts");
                for (boolean agent : List.of(false, true)) {
                        contracts.add(InterviewOutputContract.contract(true, agent, false));
                        contracts.add(InterviewOutputContract.contract(false, agent, false));
                        contracts.add(InterviewOutputContract.contract(false, agent, true));
                }
                contracts.add(InterviewOutputContract.contract(false, true, false, true));
                contracts.add(InterviewOutputContract.contract(false, true, true, true));
                var renderedContracts = manifest.putArray("renderedContracts");
                for (boolean agent : List.of(false, true)) {
                        renderedContracts.add(InterviewOutputContract.prompt(true, agent, false));
                        renderedContracts.add(InterviewOutputContract.prompt(false, agent, false));
                        renderedContracts.add(InterviewOutputContract.prompt(false, agent, true));
                }
                renderedContracts.add(InterviewOutputContract.prompt(false, true, false, true));
                renderedContracts.add(InterviewOutputContract.prompt(false, true, true, true));
                manifest.putObject("generation")
                                .put("openingTemperature", 0.55)
                                .put("openingMaxTokens", 600)
                                .put("reviewTemperature", 0.35)
                                .put("agentReviewMaxTokens", 2000)
                                .put("legacyReviewMaxTokens", 1400);
                return manifest;
        }

        private boolean putContext(ObjectNode input, String context, InterviewAgentContext agent) {
                // 在入口只解码一次：旧调用传材料，新调用传带前缀的 Agent 上下文；出站字段只放材料。
                var difficulty = agent == null ? InterviewDifficulty.STANDARD : agent.difficulty();
                input.put("interviewDifficulty", difficulty.name());
                input.put("difficultyGuidance", difficulty.guidance());
                input.put("projectFocused", agent != null && agent.projectFocused());
                input.put("resumeSnapshot", agent == null ? normalizeNullable(context) : agent.materials());
                // 缺少检查点时维持普通面试协议，不能仅因输入有封装就要求模型输出 Agent 决策。
                if (agent == null || agent.checkpoint() == null)
                        return false;

                var policy = new InterviewAgentPolicy();
                boolean adaptive = InterviewAgentPolicy.adaptive(agent.checkpoint());
                // 控制区只投影策略认可的能力和路由；材料锚点、历史引文统一放入不可信证据区。
                putCheckpoint(input.putObject("agentCheckpoint"), agent, policy);
                putRouting(input.putObject("agentRouting"), agent, policy, adaptive);
                putEvidence(input.putObject("untrustedEvidence"), agent, adaptive);
                return true;
        }

        private void putCheckpoint(
                        ObjectNode memory, InterviewAgentContext agent, InterviewAgentPolicy policy) {
                var checkpoint = agent.checkpoint();
                memory.put("version", checkpoint.version());
                memory.set(
                                "plan",
                                objectMapper.valueToTree(
                                                checkpoint.plan().stream().map(policy::controlled).toList()));
                memory.put("activeCompetencyId", checkpoint.activeCompetencyId());
                if (checkpoint.training() != null)
                        memory.set("trainingTopic", objectMapper.valueToTree(checkpoint.training().topic()));
        }

        private void putRouting(
                        ObjectNode routing,
                        InterviewAgentContext agent,
                        InterviewAgentPolicy policy,
                        boolean adaptive) {
                var route = agent.routing();
                routing.set("current", objectMapper.valueToTree(policy.controlled(route.current())));
                routing.set(
                                "next",
                                route.next() == null
                                                ? objectMapper.nullNode()
                                                : objectMapper.valueToTree(policy.controlled(route.next())));
                if (adaptive) {
                        routing.put("strategy", "adaptive-v1");
                        // 不预选下一项，由模型在范围内尚未访问的能力中选择。
                        routing.remove("next");
                        routing.set(
                                        "eligibleNext",
                                        objectMapper.valueToTree(
                                                        route.eligibleNext().stream().map(policy::controlled)
                                                                        .toList()));
                        routing.set(
                                        "coveredCompetencyIds",
                                        objectMapper.valueToTree(
                                                        agent.checkpoint().evaluations().stream()
                                                                        .map(e -> e.competencyId())
                                                                        .distinct()
                                                                        .toList()));
                }
                routing.set("allowedActions", objectMapper.valueToTree(route.allowedActions()));
                routing.set("allowedProbes", objectMapper.valueToTree(route.allowedProbes()));
                routing.put("remainingRounds", route.remainingRounds())
                                .put("followUpBudget", route.followUpBudget())
                                .put("maxFollowUpsPerCompetency", route.maxFollowUpsPerCompetency());
        }

        private void putEvidence(ObjectNode evidence, InterviewAgentContext agent, boolean adaptive) {
                var training = agent.checkpoint().training();
                if (training != null && training.knowledge() != null) {
                        var references = agent.knowledgeReferences() != null
                                        ? agent.knowledgeReferences()
                                        : training.knowledge().stream()
                                                        .map(
                                                                        d -> new KnowledgeSnapshot.Reference(
                                                                                        d.id(),
                                                                                        d.title(),
                                                                                        d.version(),
                                                                                        d.sha256(),
                                                                                        d.excerpts()))
                                                        .toList();
                        evidence.set("knowledgeReferences", objectMapper.valueToTree(references));
                }
                if (training != null) {
                        if (training.objective() != null)
                                evidence.put("trainingObjective", training.objective());
                        evidence.set("retry", objectMapper.valueToTree(training.retry()));
                        evidence.set("questionReference", objectMapper.valueToTree(training.question()));
                }
                putMaterialAnchors(evidence, agent, adaptive);
                putReviewHistory(evidence, agent, adaptive);
        }

        private void putMaterialAnchors(
                        ObjectNode evidence, InterviewAgentContext agent, boolean adaptive) {
                var route = agent.routing();
                var anchors = evidence.putArray("materialAnchors");
                boolean legacyObjectives = !adaptive
                                && !List.of("interview-agent-v3", "interview-agent-v4")
                                                .contains(agent.checkpoint().version());
                for (var competency : agent.checkpoint().plan()) {
                        if (!competency.id().equals(route.current().id())
                                        && route.eligibleNext().stream()
                                                        .noneMatch(next -> next.id().equals(competency.id())))
                                continue;
                        var entry = anchors.addObject().put("competencyId", competency.id());
                        entry.set("quotes", objectMapper.valueToTree(competency.materialAnchors()));
                        if (legacyObjectives)
                                entry.put("legacyObjectiveQuote", competency.objective());
                }
        }

        private void putReviewHistory(
                        ObjectNode evidence, InterviewAgentContext agent, boolean adaptive) {
                var checkpoint = agent.checkpoint();
                var route = agent.routing();
                // 压缩对话记录后，通过摘录摘要保留各历史轮次已校验的证据。
                var digest = checkpoint.digest().isEmpty()
                                ? checkpoint.evaluations().stream()
                                                .map(
                                                                e -> new InterviewAgentState.DigestEntry(
                                                                                e.round(),
                                                                                e.competencyId(),
                                                                                e.decision().level(),
                                                                                excerpt(e.decision().evidence(), 120),
                                                                                excerpt(e.decision().gap(), 100),
                                                                                e.answeredProbe()))
                                                .toList()
                                : checkpoint.digest();
                evidence.set("digest", objectMapper.valueToTree(digest));
                if (InterviewAgentPolicy.planning(checkpoint))
                        evidence.set("planningHistory", objectMapper.valueToTree(checkpoint.evaluations().stream()
                                        .map(e -> Map.of("round", e.round(), "target", e.decision().nextCompetencyId(),
                                                        "probe", e.decision().probe(), "focus", e.decision().focus()))
                                        .toList()));
                if (adaptive)
                        evidence.set(
                                        "routingDecisions",
                                        objectMapper.valueToTree(
                                                        checkpoint.evaluations().stream()
                                                                        .map(
                                                                                        e -> Map.of(
                                                                                                        "afterRound",
                                                                                                        e.round(),
                                                                                                        "from",
                                                                                                        e.competencyId(),
                                                                                                        "action",
                                                                                                        e.decision().action(),
                                                                                                        "to",
                                                                                                        e.decision().nextCompetencyId(),
                                                                                                        "reason",
                                                                                                        excerpt(e.decision()
                                                                                                                        .reason(),
                                                                                                                        100)))
                                                                        .toList()));
                evidence.set(
                                "practiceHistory",
                                objectMapper.valueToTree(
                                                checkpoint.practiceMemory().stream()
                                                                .filter(
                                                                                m -> m.competencyId().equals(
                                                                                                route.current().id())
                                                                                                || route.next() != null
                                                                                                                && m.competencyId()
                                                                                                                                .equals(route.next()
                                                                                                                                                .id()))
                                                                .sorted(
                                                                                Comparator.comparingInt(
                                                                                                (InterviewAgentState.PracticeMemory m) -> m
                                                                                                                .insufficient()
                                                                                                                * 2
                                                                                                                + m.partial())
                                                                                                .reversed())
                                                                .limit(2)
                                                                .toList()));
        }

        /** 保留最新回答的完整内容；仅压缩送入模型的上下文，存储中的完整历史不变。 */
        private String boundedInput(ObjectNode input) {
                // 先限制材料及旧轮次；最后一条回答保留原文，供评价与引文定位使用。
                String materials = input.path("resumeSnapshot").asString();
                if (materials.length() > 6000) {
                        input.put("resumeSnapshot", materials.substring(0, 6000));
                        input.put("contextTruncated", true);
                }
                var transcript = input.path("transcript");
                if (transcript.isArray()) {
                        for (int i = 0; i < transcript.size() - 1; i++) {
                                var turn = (ObjectNode) transcript.get(i);
                                String text = turn.path("content").asString();
                                int limit = "user".equals(turn.path("speaker").asString()) ? 600 : 300;
                                if (text.length() > limit) {
                                        turn.put("content", text.substring(0, limit) + "（历史内容已截断）");
                                        input.put("contextTruncated", true);
                                }
                        }
                }
                // 按 JSON 序列化后的 UTF-16 长度计数，含转义开销；30k 为通用 32k 限制留余量，不是 token 预算。
                // 降级顺序：材料减半至空 → 从最旧轮次移除（保留末条）→ 仅一次压缩证据摘要。
                // 不裁剪控制约束或最新回答；仍超预算则失败，避免靠丢失关键证据凑出可发送请求。
                while (input.toString().length() > 30000) {
                        input.put("contextTruncated", true);
                        String source = input.path("resumeSnapshot").asString();
                        if (!source.isEmpty())
                                input.put("resumeSnapshot", source.substring(0, source.length() / 2));
                        else if (transcript.isArray() && transcript.size() > 1)
                                ((ArrayNode) transcript).remove(0);
                        else if (!input.path("digestCondensed").asBoolean()) {
                                // 标记保证摘要最多压缩一次，即使无可压缩内容也会在下轮退出，不会死循环。
                                input.put("digestCondensed", true);
                                for (var entry : input.path("untrustedEvidence").path("digest")) {
                                        ((ObjectNode) entry)
                                                        .put("evidence", excerpt(entry.path("evidence").asString(), 16))
                                                        .put("gap", excerpt(entry.path("gap").asString(), 16));
                                }
                                for (var entry : input.path("untrustedEvidence").path("materialAnchors")) {
                                        ((ObjectNode) entry).remove("legacyObjectiveQuote");
                                        for (var quote : entry.path("quotes"))
                                                ((ObjectNode) quote)
                                                                .put("quote", excerpt(quote.path("quote").asString(),
                                                                                24));
                                }
                                var retry = input.path("untrustedEvidence").path("retry");
                                if (retry.isObject()) {
                                        var snapshot = (ObjectNode) retry;
                                        snapshot.put("answer", excerpt(retry.path("answer").asString(), 120))
                                                        .put("question", excerpt(retry.path("question").asString(),
                                                                        120))
                                                        .put("evidence", excerpt(retry.path("evidence").asString(), 80))
                                                        .put("gap", excerpt(retry.path("gap").asString(), 160));
                                }
                        } else
                                throw StructuredOutput.invalid("agentInputBudget");
                }
                return input.toString();
        }

        private String excerpt(String value, int max) {
                String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
                return text.substring(0, Math.min(max, text.length()));
        }

}
