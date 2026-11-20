package cn.edu.whu.zhizhida.interview.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Action;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.AnchorKind;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Competency;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Decision;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.DigestEntry;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Evaluation;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Level;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.MaterialAnchor;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.PracticeMemory;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Probe;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.Candidate;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.EvidenceGap;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.GapStatus;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.PlanRevision;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.QuestionIntent;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanningContext;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;

/** 在限定范围内规划、观察并行动；模型决策不能扩展计划或预算。 */
public final class InterviewAgentPolicy {
    public static final String ADAPTIVE_VERSION = "interview-agent-v6";

    public static boolean adaptive(InterviewAgentState state) {
        return planning(state) || "interview-agent-v5".equals(state.version());
    }

    public static boolean planning(InterviewAgentState state) {
        return ADAPTIVE_VERSION.equals(state.version());
    }

    /** 新教学会话冻结策略，历史会话沿用原策略。 */
    public InterviewAgentState adaptivePlan(InterviewAgentState state) {
        if (!state.evaluations().isEmpty())
            throw new IllegalArgumentException("Only a new interview can select adaptive routing");
        return new InterviewAgentState(ADAPTIVE_VERSION, state.executionMode(), state.plan(),
                state.activeCompetencyId(), state.evaluations(), state.digest(), state.practiceMemory(),
                state.training(), state.memoryReferences(), state.planningRevisions());
    }

    private static final List<Probe> PROBES = List.of(Probe.EVIDENCE, Probe.QUANTIFY, Probe.COUNTEREXAMPLE,
            Probe.TRANSFER, Probe.TRADEOFF, Probe.CLARIFY);

    public InterviewAgentState plan(InterviewMode mode, int rounds, String job, String executionMode) {
        return plan(mode, rounds, new InterviewPlanningContext(job, List.of(), List.of(), List.of(), ""),
                executionMode);
    }

    public InterviewAgentState plan(InterviewMode mode, int rounds, InterviewPlanningContext materials,
            String executionMode) {
        return plan(mode, rounds, materials, executionMode, List.of());
    }

    private List<Competency> catalog(InterviewMode mode) {
        return switch (mode) {
            case PROJECT_DEEP_DIVE -> List.of(
                    new Competency("ownership", "项目职责", "说明业务问题、约束和个人职责"),
                    new Competency("tradeoffs", "方案取舍", "比较备选方案，解释选择及接受的代价"),
                    new Competency("verification", "结果验证", "说明实际验证方法、结果与限制"),
                    new Competency("architecture", "架构边界", "说明模块接口与复杂度控制"),
                    new Competency("debugging", "故障定位", "用排查证据解释根因及修复验证"),
                    new Competency("performance", "性能优化", "解释性能瓶颈、测量基线与优化收益"),
                    new Competency("collaboration", "协作交付", "说明职责交接、分歧处理与交付过程"),
                    new Competency("reflection", "复盘迁移", "复盘失败边界及新场景下的改进"));
            case BACKEND_BASICS -> List.of(
                    new Competency("foundation", "基础与实践", "结合实际场景解释核心概念"),
                    new Competency("design", "技术决策", "解释接口、架构设计选择与边界"),
                    new Competency("debugging", "问题定位", "说明排查顺序、证据和验证方法"),
                    new Competency("storage", "存储与一致性", "分析数据库索引、事务与缓存一致性"),
                    new Competency("concurrency", "并发控制", "解释线程安全、幂等与并发边界"),
                    new Competency("performance", "性能分析", "说明性能测量、瓶颈与优化验证"),
                    new Competency("testing", "测试验证", "说明测试设计、覆盖范围和回归策略"),
                    new Competency("reliability", "工程可靠性", "分析异常恢复、监控与可靠性取舍"));
            case GENERAL -> List.of(
                    new Competency("experience", "岗位经历", "说明与目标岗位的关联和个人贡献"),
                    new Competency("problem", "问题解决", "解释判断过程、行动及方案取舍"),
                    new Competency("collaboration", "协作沟通", "结合具体分歧说明如何沟通并推进任务"),
                    new Competency("expertise", "专业实践", "解释岗位所需技能的实际应用和边界"),
                    new Competency("delivery", "目标交付", "说明优先级、进度风险与交付验证"),
                    new Competency("learning", "学习适应", "说明如何学习陌生领域并验证理解"),
                    new Competency("judgment", "风险判断", "解释信息不足时的判断、风险与替代方案"),
                    new Competency("reflection", "成果复盘", "用可核对的结果复盘经验与改进"));
        };
    }

    public InterviewAgentState plan(InterviewMode mode, int rounds, InterviewPlanningContext materials,
            String executionMode,
            List<PracticeMemory> memory) {
        var catalog = catalog(mode);
        var ranked = new ArrayList<>(catalog.subList(1, catalog.size()));
        ranked.sort(Comparator.comparingInt((Competency c) -> relevance(c, materials)
                + Math.min(3, memory.stream().filter(m -> m.competencyId().equals(c.id()))
                        .mapToInt(m -> m.insufficient() * 2 + m.partial()).sum()))
                .reversed());
        ranked.add(0, catalog.get(0));
        int size = Math.min(rounds, Math.min(8, (2 * rounds + 2) / 3 + 1));
        var plan = new ArrayList<Competency>();
        var selected = ranked.subList(0, size);
        var projectAssignments = assignProjects(selected, materials);
        for (var c : selected)
            plan.add(personalize(c, materials, projectAssignments.get(c.id())));
        return new InterviewAgentState("interview-agent-v3", executionMode, plan, plan.get(0).id(), List.of(),
                List.of(), memory);
    }

    public InterviewAgentState trainingPlan(InterviewTraining training, InterviewPlanningContext materials,
            String executionMode, List<PracticeMemory> memory, Competency original) {
        List<Competency> selected;
        if (training.question() != null)
            selected = catalog(InterviewMode.BACKEND_BASICS).stream()
                    .filter(c -> c.id().equals(training.question().competencyId())).toList();
        else if (training.retry() != null)
            selected = List.of(original);
        else if (training.topic() == InterviewTraining.Topic.PROJECT)
            selected = catalog(InterviewMode.PROJECT_DEEP_DIVE).subList(0, 2);
        else {
            String id = switch (training.topic()) {
                case DATABASE -> "storage";
                case CONCURRENCY -> "concurrency";
                case PERFORMANCE -> "performance";
                case PROJECT -> throw new IllegalStateException();
            };
            selected = catalog(InterviewMode.BACKEND_BASICS).stream().filter(c -> c.id().equals(id)).toList();
        }
        var assignments = assignProjects(selected, materials);
        var plan = selected.stream().map(c -> training.retry() != null
                ? new Competency(c.id(), controlled(c).name(), controlled(c).objective(), c.materialAnchors())
                : personalize(controlled(c), materials, assignments.get(c.id()))).toList();
        return new InterviewAgentState("interview-training-v1", executionMode, plan, plan.get(0).id(),
                List.of(), List.of(), memory, training);
    }

    /** 始终从能力目录重建控制文本，避免旧计划目标中夹带的用户引文成为控制指令。 */
    public Competency controlled(Competency competency) {
        var candidates = java.util.Arrays.stream(InterviewMode.values()).flatMap(m -> catalog(m).stream())
                .filter(c -> c.id().equals(competency.id())).toList();
        if (candidates.isEmpty())
            throw new IllegalStateException("Unknown server competency ID");
        return candidates.stream().filter(c -> c.name().equals(competency.name())).findFirst()
                .orElse(candidates.get(0));
    }

    private int relevance(Competency c, InterviewPlanningContext m) {
        return matches(c, m.job()) * 3 + matches(c, String.join(" ", m.skills())) + m.projects().stream()
                .mapToInt(p -> matches(c, p.contributions() + " " + p.technologies())).max().orElse(0);
    }

    private int matches(Competency c, String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        var terms = switch (c.id()) {
            case "storage" -> List.of("数据库", "索引", "事务", "缓存", "sql", "redis", "一致性");
            case "concurrency" -> List.of("并发", "线程", "幂等", "锁");
            case "performance" -> List.of("性能", "吞吐", "延迟", "优化", "压测");
            case "testing", "verification", "reflection" -> List.of("测试", "验证", "指标", "结果", "复盘");
            case "reliability", "judgment", "debugging" -> List.of("故障", "可靠", "排查", "监控", "风险", "异常");
            case "collaboration", "delivery" -> List.of("协作", "沟通", "团队", "交付", "进度");
            case "design", "architecture", "tradeoffs", "problem" -> List.of("设计", "架构", "方案", "接口", "取舍");
            case "learning" -> List.of("学习", "适应", "研究");
            case "experience", "ownership" -> List.of("职责", "负责", "项目", "实习", "贡献");
            default -> List.of("技能", "实践", "经验", "开发");
        };
        return (int) terms.stream().filter(normalized::contains).count();
    }

    private int projectScore(Competency c, InterviewPlanningContext.Project p) {
        return matches(c, p.name() + " " + p.role() + " " + p.contributions() + " " + p.technologies());
    }

    private Map<String, Integer> assignProjects(List<Competency> selected, InterviewPlanningContext m) {
        var assignments = new HashMap<String, Integer>();
        var uses = new HashMap<String, Integer>();
        var byRelevance = selected.stream()
                .sorted(Comparator.comparingInt(
                        (Competency c) -> m.projects().stream().mapToInt(p -> projectScore(c, p)).max().orElse(0))
                        .reversed())
                .toList();
        for (var c : byRelevance) {
            int chosen = -1, score = 0;
            for (int i = 0; i < m.projects().size(); i++) {
                var p = m.projects().get(i);
                int relevance = projectScore(c, p);
                if (relevance > score && uses.getOrDefault(clip(p.name(), 120), 0) < 2) {
                    chosen = i;
                    score = relevance;
                }
            }
            if (chosen >= 0) {
                assignments.put(c.id(), chosen);
                uses.merge(clip(m.projects().get(chosen).name(), 120), 1, Integer::sum);
            }
        }
        return assignments;
    }

    private Competency personalize(Competency c, InterviewPlanningContext m, Integer projectIndex) {
        var anchors = new ArrayList<MaterialAnchor>();
        if (projectIndex != null) {
            var p = m.projects().get(projectIndex);
            anchors.add(new MaterialAnchor(AnchorKind.PROJECT, "projects[" + projectIndex + "]",
                    "项目：" + clip(p.name(), 80) + "；职责：" + clip(p.role(), 40) + "；贡献：" + clip(p.contributions(), 120)));
        }
        m.internships().stream().filter(s -> matches(c, s) > 0).findFirst()
                .ifPresent(s -> anchors.add(new MaterialAnchor(AnchorKind.INTERNSHIP, "internships", clip(s, 120))));
        var skills = m.skills().stream().filter(s -> contains(m.job(), s)).limit(3).toList();
        if (skills.isEmpty())
            skills = m.skills().stream().limit(2).toList();
        if (!skills.isEmpty() && (c.id().equals("foundation") || c.id().equals("expertise")))
            anchors.add(new MaterialAnchor(AnchorKind.SKILLS, "skills", clip(String.join("、", skills), 100)));
        if (anchors.isEmpty() && matches(c, m.summary()) > 0)
            anchors.add(new MaterialAnchor(AnchorKind.SUMMARY, "summary", clip(m.summary(), 120)));
        String clause = List.of((m.job() == null ? "" : m.job()).split("[\n；。]")).stream()
                .filter(s -> matches(c, s) > 0).max(Comparator.comparingInt(s -> matches(c, s))).orElse("");
        if (!clause.isEmpty())
            anchors.add(new MaterialAnchor(AnchorKind.JOB, "job", clip(clause, 160)));
        return new Competency(c.id(), c.name(), c.objective(), anchors);
    }

    private boolean contains(String text, String term) {
        return !blank(term) && text != null && text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String clip(String text, int max) {
        String clean = text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public record Routing(Competency current, Competency next, List<Action> allowedActions, int remainingRounds,
            int followUpBudget, List<Probe> allowedProbes, int maxFollowUpsPerCompetency,
            List<Competency> eligibleNext) {
        public Routing {
            // 旧上下文只有 next，回放时不得获得新的路由权限。
            eligibleNext = eligibleNext == null ? next == null ? List.of() : List.of(next) : List.copyOf(eligibleNext);
        }

        public Routing(Competency current, Competency next, List<Action> actions, int remaining,
                int budget, List<Probe> probes, int cap) {
            this(current, next, actions, remaining, budget, probes, cap, null);
        }
    }

    public Routing routing(InterviewAgentState state, int answered, int total) {
        if (adaptive(state))
            return adaptiveRouting(state, answered, total);
        int index = 0;
        while (index < state.plan().size() - 1 && !state.plan().get(index).id().equals(state.activeCompetencyId()))
            index++;
        var current = state.plan().get(index);
        var next = index + 1 < state.plan().size() ? state.plan().get(index + 1) : null;
        int remaining = total - answered;
        int budget = Math.max(0, total - Math.max(1, answered) - (state.plan().size() - index - 1));
        var used = state.evaluations().stream().filter(e -> e.competencyId().equals(current.id())
                && e.decision().action() == Action.FOLLOW_UP).map(e -> e.decision().probe()).toList();
        // V4 规划已退役；历史会话保留版本与规划记录，后续命令沿用 V3 固定路由。
        int cap = state.training() != null ? (state.plan().size() == 1 ? 5 : 2)
                : List.of("interview-agent-v3", "interview-agent-v4").contains(state.version()) ? 2 : 11;
        var allowed = new ArrayList<Action>();
        if (remaining == 0)
            allowed.add(Action.FINISH);
        else {
            if (budget > 0 && used.size() < cap)
                allowed.add(Action.FOLLOW_UP);
            // 推进到下一能力前须保留足够容量，确保剩余轮次不会全部挤在计划末尾。
            if (next != null && remaining <= (state.plan().size() - index - 1) * (cap + 1))
                allowed.add(Action.ADVANCE);
        }
        long min = PROBES.stream().mapToLong(p -> used.stream().filter(p::equals).count()).min().orElse(0);
        var probes = PROBES.stream().filter(p -> used.stream().filter(p::equals).count() == min
                && (used.isEmpty() || used.get(used.size() - 1) != p)).toList();
        return new Routing(current, next, allowed, remaining, budget,
                allowed.contains(Action.FOLLOW_UP) ? probes : List.of(), cap);
    }

    private Routing adaptiveRouting(InterviewAgentState state, int answered, int total) {
        var ids = state.plan().stream().map(Competency::id).toList();
        var covered = state.evaluations().stream().map(Evaluation::competencyId)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty() || ids.stream().distinct().count() != ids.size()
                || !ids.containsAll(covered) || total < ids.size() || total > 12
                || answered < 0 || answered > total
                || (answered == 0 ? !state.evaluations().isEmpty() : answered != state.evaluations().size() + 1))
            throw new IllegalArgumentException("Adaptive routing checkpoint or round is inconsistent");
        var current = state.plan().stream().filter(c -> c.id().equals(state.activeCompetencyId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown active competency"));
        var unvisited = state.plan().stream().filter(c -> !c.id().equals(current.id()) && !covered.contains(c.id()))
                .toList();
        int remaining = total - Math.max(1, answered);
        int budget = Math.max(0, remaining - unvisited.size());
        var used = state.evaluations().stream().filter(e -> e.competencyId().equals(current.id())
                && e.decision().action() == Action.FOLLOW_UP).map(e -> e.decision().probe()).toList();
        var probes = planning(state) ? PROBES : PROBES.stream().filter(p -> !used.contains(p)).toList();
        var allowed = new ArrayList<Action>();
        if (answered == total && unvisited.isEmpty())
            allowed.add(Action.FINISH);
        else if (remaining >= unvisited.size()) {
            if (budget > 0 && !probes.isEmpty())
                allowed.add(Action.FOLLOW_UP);
            if (!unvisited.isEmpty() && remaining <= unvisited.size() * (PROBES.size() + 1))
                allowed.add(Action.ADVANCE);
        }
        if (allowed.isEmpty() && answered != 0)
            throw new IllegalArgumentException("Adaptive routing cannot satisfy coverage and rounds");
        var eligible = allowed.contains(Action.ADVANCE) ? unvisited : List.<Competency>of();
        return new Routing(current, eligible.isEmpty() ? null : eligible.getFirst(), allowed,
                total - answered, budget, allowed.contains(Action.FOLLOW_UP) ? probes : List.of(), PROBES.size(),
                eligible);
    }

    public InterviewAgentState advance(InterviewAgentState state, int answered, int total, String answer,
            Decision decision) {
        return advance(state, answered, total, answer, decision, "", "", InterviewDifficulty.STANDARD, "");
    }

    public InterviewAgentState advance(InterviewAgentState state, int answered, int total, String answer,
            Decision decision,
            String sessionId, String answerVersion, InterviewDifficulty difficulty, String nextQuestion) {
        var route = routing(state, answered, total);
        if (decision == null || decision.level() == null || decision.action() == null
                || !route.allowedActions().contains(decision.action()))
            throw new IllegalArgumentException("agentDecision.action 必须属于 allowedActions=" + route.allowedActions());
        String expected = route.current().id();
        if (decision.action() == Action.ADVANCE) {
            if (adaptive(state)
                    && route.eligibleNext().stream().noneMatch(c -> c.id().equals(decision.nextCompetencyId())))
                throw new IllegalArgumentException("agentDecision.nextCompetencyId 必须属于 eligibleNext");
            expected = adaptive(state) ? decision.nextCompetencyId() : route.next().id();
        }
        if (!expected.equals(decision.nextCompetencyId()))
            throw new IllegalArgumentException("agentDecision.nextCompetencyId 必须为 " + expected);
        if (!java.util.Objects.equals(decision.questionCompetencyId(),
                decision.action() == Action.FINISH ? "" : expected))
            throw new IllegalArgumentException("agentDecision.questionCompetencyId 必须与下一题目标一致："
                    + (decision.action() == Action.FINISH ? "空字符串（结束）" : expected));
        if (decision.evidence() == null || decision.evidence().length() > 500
                || (!decision.evidence().isEmpty()
                        && (!answer.contains(decision.evidence()) || decision.evidence().isBlank()))
                || (decision.level() != Level.INSUFFICIENT && decision.evidence().length() < 4))
            throw new IllegalArgumentException("agentDecision.evidence 必须逐字引用最新回答，最多500字；非 INSUFFICIENT 至少4字");
        if (adaptive(state)
                && (decision.reason() == null || decision.reason().isBlank() || decision.reason().length() > 300
                        || decision.action() == Action.ADVANCE && decision.evidence().isBlank()))
            throw new IllegalArgumentException("动态决策必须提供简短依据；切换能力须引用当前回答");
        if (decision.probe() == null || decision.intent() == null || decision.intent().isBlank()
                || decision.intent().length() > 200)
            throw new IllegalArgumentException("agentDecision.probe 必填；intent 必须为1至200字的提问意图");
        if (decision.action() == Action.FOLLOW_UP ? !route.allowedProbes().contains(decision.probe())
                : decision.probe() != (decision.action() == Action.ADVANCE ? Probe.OPENING : Probe.NONE))
            throw new IllegalArgumentException("agentDecision.probe 不符合动作或重复策略；FOLLOW_UP 可用策略=" + route.allowedProbes()
                    + "，ADVANCE 使用 OPENING，FINISH 使用 NONE");
        if (planning(state))
            validateFocus(state, decision);
        var previous = state.evaluations().isEmpty() ? null
                : state.evaluations().get(state.evaluations().size() - 1).decision();
        var evaluations = new ArrayList<>(state.evaluations());
        evaluations.add(new Evaluation(answered, route.current().id(), decision,
                previous == null ? Probe.OPENING : previous.probe(),
                previous == null ? route.current().objective() : previous.intent()));
        var digest = evaluations.stream().map(e -> new DigestEntry(e.round(), e.competencyId(), e.decision().level(),
                clip(e.decision().evidence(), 120), clip(e.decision().gap(), 100), e.answeredProbe())).toList();
        var revisions = new ArrayList<>(state.planningRevisions());
        if (planning(state))
            revisions.add(revision(revisions.size() + 1, answered, sessionId, answerVersion, answer, decision,
                    difficulty, nextQuestion));
        return new InterviewAgentState(state.version(), state.executionMode(), state.plan(), expected, evaluations,
                digest, state.practiceMemory(), state.training(), state.memoryReferences(), revisions);
    }

    private void validateFocus(InterviewAgentState state, Decision decision) {
        var focus = decision.focus();
        if (focus == null || focus.dimension() == null || focus.scenario() == null
                || blank(focus.claimToVerify()) || focus.claimToVerify().length() > 200
                || blank(focus.expectedEvidence()) || focus.expectedEvidence().length() > 200)
            throw new IllegalArgumentException("agentDecision.focus 必须包含维度、场景、待核对主张和预期证据，文本各为1至200字");
        if (decision.action() == Action.FINISH)
            return;
        boolean repeated = state.evaluations().stream().map(Evaluation::decision)
                .filter(prior -> prior.focus() != null && prior.action() != Action.FINISH)
                .anyMatch(prior -> prior.nextCompetencyId().equals(decision.nextCompetencyId())
                        && prior.focus().scenario() == focus.scenario()
                        && normalized(prior.focus().claimToVerify()).equals(normalized(focus.claimToVerify())));
        if (repeated)
            throw new IllegalArgumentException("agentDecision.focus 重复已验证目标；请依据新缺口选择主张或场景");
    }

    private String normalized(String text) {
        return text.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private PlanRevision revision(int revision, int round, String sessionId, String answerVersion, String answer,
            Decision decision,
            InterviewDifficulty difficulty, String nextQuestion) {
        if (decision.action() == Action.FINISH)
            return new PlanRevision(revision, round, ADAPTIVE_VERSION, difficulty, List.of(), List.of(), "", false,
                    decision.reason());
        var focus = decision.focus();
        String quote = decision.evidence();
        int start = quote.isEmpty() ? -1 : answer.indexOf(quote);
        if (start >= 0 && answer.indexOf(quote, start + 1) >= 0)
            throw new IllegalArgumentException("agentDecision.evidence 必须唯一定位本轮回答中的依据");
        int end = start < 0 ? -1 : start + quote.length();
        String source = sessionId + ":answer:" + round;
        var gap = new EvidenceGap("gap-" + round, focus.dimension(),
                quote.isEmpty() ? GapStatus.NOT_OBSERVED : GapStatus.NEEDS_VERIFICATION,
                decision.gap(), source, answerVersion, "RAW_UTF16", start, end, quote);
        var intent = new QuestionIntent(decision.nextCompetencyId(), decision.probe(), focus.dimension(),
                focus.scenario(),
                focus.claimToVerify(), focus.expectedEvidence(), quote, start, end, difficulty, nextQuestion);
        String key = "intent-" + round;
        return new PlanRevision(revision, round, ADAPTIVE_VERSION, difficulty,
                List.of(new Candidate(key, intent, gap, 1)), List.of(), key, false, decision.reason());
    }

    /** 仅拦截明显偏离能力主题的技术问题；元数据匹配不能证明语义一致。 */
    public void validateQuestion(String competencyId, String question) {
        if (!List.of("storage", "concurrency", "performance").contains(competencyId))
            return;
        var target = new Competency(competencyId, "", "");
        if (matches(target, question) > 0)
            return;
        if (List.of("storage", "concurrency", "performance").stream().filter(id -> !id.equals(competencyId))
                .anyMatch(id -> matches(new Competency(id, "", ""), question) > 0))
            throw new IllegalArgumentException("questionCompetencyId 与问题技术主题不一致，请围绕 " + competencyId + " 重新提问");
    }
}
