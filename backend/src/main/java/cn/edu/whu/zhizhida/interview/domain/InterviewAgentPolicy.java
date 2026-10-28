package cn.edu.whu.zhizhida.interview.domain;

import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanningContext;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Bounded plan/observe/act loop; model decisions cannot expand the plan or budget. */
public final class InterviewAgentPolicy {
    private static final List<Probe> PROBES = List.of(Probe.EVIDENCE, Probe.QUANTIFY, Probe.COUNTEREXAMPLE,
            Probe.TRANSFER, Probe.TRADEOFF, Probe.CLARIFY);

    public InterviewAgentState plan(InterviewMode mode, int rounds, String job, String executionMode) {
        return plan(mode, rounds, new InterviewPlanningContext(job, List.of(), List.of(), List.of(), ""), executionMode);
    }

    public InterviewAgentState plan(InterviewMode mode, int rounds, InterviewPlanningContext materials, String executionMode) {
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

    public InterviewAgentState plan(InterviewMode mode, int rounds, InterviewPlanningContext materials, String executionMode,
                                    List<PracticeMemory> memory) {
        var catalog = catalog(mode);
        var ranked = new ArrayList<>(catalog.subList(1, catalog.size()));
        ranked.sort(Comparator.comparingInt((Competency c) -> relevance(c, materials)
                + Math.min(3, memory.stream().filter(m -> m.competencyId().equals(c.id()))
                .mapToInt(m -> m.insufficient() * 2 + m.partial()).sum())).reversed());
        ranked.add(0, catalog.get(0));
        int size = Math.min(rounds, Math.min(8, (2 * rounds + 2) / 3 + 1));
        var plan = new ArrayList<Competency>();
        var selected = ranked.subList(0, size);
        var projectAssignments = assignProjects(selected, materials);
        for (var c : selected) plan.add(personalize(c, materials, projectAssignments.get(c.id())));
        return new InterviewAgentState("interview-agent-v3", executionMode, plan, plan.get(0).id(), List.of(), List.of(), memory);
    }

    public InterviewAgentState trainingPlan(InterviewTraining training, InterviewPlanningContext materials,
            String executionMode, List<PracticeMemory> memory, Competency original) {
        List<Competency> selected;
        if (training.retry() != null) selected = List.of(original);
        else if (training.topic() == InterviewTraining.Topic.PROJECT) selected = catalog(InterviewMode.PROJECT_DEEP_DIVE).subList(0, 2);
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

    /** Reconstruct control text from the catalog even for legacy plans whose objectives contain user quotations. */
    public Competency controlled(Competency competency) {
        var candidates = java.util.Arrays.stream(InterviewMode.values()).flatMap(m -> catalog(m).stream())
                .filter(c -> c.id().equals(competency.id())).toList();
        if (candidates.isEmpty()) throw new IllegalStateException("Unknown server competency ID");
        return candidates.stream().filter(c -> c.name().equals(competency.name())).findFirst().orElse(candidates.get(0));
    }

    public List<PracticeMemory> practiceMemory(List<InterviewAgentState> history, String executionMode) {
        var groups = new HashMap<String, List<Evaluation>>();
        for (var state : history) {
            if (!executionMode.equals(state.executionMode())) continue;
            for (var e : state.evaluations()) {
                if (e.answeredProbe() == null || e.answeredProbe() == Probe.NONE) continue;
                String key = e.competencyId() + ":" + e.answeredProbe().name();
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(e);
            }
        }
        return groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var values = entry.getValue(); var first = values.get(0);
            return new PracticeMemory(first.competencyId(), first.answeredProbe(), values.size(),
                    (int) values.stream().filter(e -> e.decision().level() == Level.INSUFFICIENT).count(),
                    (int) values.stream().filter(e -> e.decision().level() == Level.PARTIAL).count());
        }).toList();
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
        var byRelevance = selected.stream().sorted(Comparator.comparingInt((Competency c) ->
                m.projects().stream().mapToInt(p -> projectScore(c, p)).max().orElse(0)).reversed()).toList();
        for (var c : byRelevance) {
            int chosen = -1, score = 0;
            for (int i = 0; i < m.projects().size(); i++) {
                var p = m.projects().get(i);
                int relevance = projectScore(c, p);
                if (relevance > score && uses.getOrDefault(clip(p.name(), 120), 0) < 2) { chosen = i; score = relevance; }
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
        m.internships().stream().filter(s -> matches(c, s) > 0).findFirst().ifPresent(s ->
                anchors.add(new MaterialAnchor(AnchorKind.INTERNSHIP, "internships", clip(s, 120))));
        var skills = m.skills().stream().filter(s -> contains(m.job(), s)).limit(3).toList();
        if (skills.isEmpty()) skills = m.skills().stream().limit(2).toList();
        if (!skills.isEmpty() && (c.id().equals("foundation") || c.id().equals("expertise")))
            anchors.add(new MaterialAnchor(AnchorKind.SKILLS, "skills", clip(String.join("、", skills), 100)));
        if (anchors.isEmpty() && matches(c, m.summary()) > 0)
            anchors.add(new MaterialAnchor(AnchorKind.SUMMARY, "summary", clip(m.summary(), 120)));
        String clause = List.of((m.job() == null ? "" : m.job()).split("[\n；。]")).stream()
                .filter(s -> matches(c, s) > 0).max(Comparator.comparingInt(s -> matches(c, s))).orElse("");
        if (!clause.isEmpty()) anchors.add(new MaterialAnchor(AnchorKind.JOB, "job", clip(clause, 160)));
        return new Competency(c.id(), c.name(), c.objective(), anchors);
    }

    private boolean contains(String text, String term) {
        return !blank(term) && text != null && text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }
    private boolean blank(String text) { return text == null || text.isBlank(); }
    private String clip(String text, int max) {
        String clean = text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public record Routing(Competency current, Competency next, List<Action> allowedActions, int remainingRounds,
                          int followUpBudget, List<Probe> allowedProbes, int maxFollowUpsPerCompetency) { }

    public Routing routing(InterviewAgentState state, int answered, int total) {
        int index = 0;
        while (index < state.plan().size() - 1 && !state.plan().get(index).id().equals(state.activeCompetencyId())) index++;
        var current = state.plan().get(index);
        var next = index + 1 < state.plan().size() ? state.plan().get(index + 1) : null;
        int remaining = total - answered;
        int budget = Math.max(0, total - Math.max(1, answered) - (state.plan().size() - index - 1));
        var used = state.evaluations().stream().filter(e -> e.competencyId().equals(current.id())
                && e.decision().action() == Action.FOLLOW_UP).map(e -> e.decision().probe()).toList();
        int cap = state.training() != null ? (state.plan().size() == 1 ? 5 : 2)
                : "interview-agent-v3".equals(state.version()) ? 2 : 11;
        var allowed = new ArrayList<Action>();
        if (remaining == 0) allowed.add(Action.FINISH);
        else {
            if (budget > 0 && used.size() < cap) allowed.add(Action.FOLLOW_UP);
            // Advancing must leave enough capacity to spend all rounds without overloading the tail.
            if (next != null && remaining <= (state.plan().size() - index - 1) * (cap + 1)) allowed.add(Action.ADVANCE);
        }
        long min = PROBES.stream().mapToLong(p -> used.stream().filter(p::equals).count()).min().orElse(0);
        var probes = PROBES.stream().filter(p -> used.stream().filter(p::equals).count() == min
                && (used.isEmpty() || used.get(used.size() - 1) != p)).toList();
        return new Routing(current, next, allowed, remaining, budget,
                allowed.contains(Action.FOLLOW_UP) ? probes : List.of(), cap);
    }

    public InterviewAgentState advance(InterviewAgentState state, int answered, int total, String answer, Decision decision) {
        var route = routing(state, answered, total);
        if (decision == null || decision.level() == null || decision.action() == null || !route.allowedActions().contains(decision.action()))
            throw new IllegalArgumentException("agentDecision.action 必须属于 allowedActions=" + route.allowedActions());
        String expected = decision.action() == Action.ADVANCE ? route.next().id() : route.current().id();
        if (!expected.equals(decision.nextCompetencyId())) throw new IllegalArgumentException("agentDecision.nextCompetencyId 必须为 " + expected);
        if (!java.util.Objects.equals(decision.questionCompetencyId(), decision.action() == Action.FINISH ? "" : expected))
            throw new IllegalArgumentException("agentDecision.questionCompetencyId 必须与下一题目标一致：" + (decision.action() == Action.FINISH ? "空字符串（结束）" : expected));
        if (decision.evidence() == null || decision.evidence().length() > 500
                || (!decision.evidence().isEmpty() && (!answer.contains(decision.evidence()) || decision.evidence().isBlank()))
                || (decision.level() != Level.INSUFFICIENT && decision.evidence().length() < 4))
            throw new IllegalArgumentException("agentDecision.evidence 必须逐字引用最新回答，最多500字；非 INSUFFICIENT 至少4字");
        if (decision.probe() == null || decision.intent() == null || decision.intent().isBlank() || decision.intent().length() > 200)
            throw new IllegalArgumentException("agentDecision.probe 必填；intent 必须为1至200字的提问意图");
        if (decision.action() == Action.FOLLOW_UP ? !route.allowedProbes().contains(decision.probe())
                : decision.probe() != (decision.action() == Action.ADVANCE ? Probe.OPENING : Probe.NONE))
            throw new IllegalArgumentException("agentDecision.probe 不符合动作或重复策略；FOLLOW_UP 可用策略=" + route.allowedProbes()
                    + "，ADVANCE 使用 OPENING，FINISH 使用 NONE");
        var previous = state.evaluations().isEmpty() ? null : state.evaluations().get(state.evaluations().size() - 1).decision();
        var evaluations = new ArrayList<>(state.evaluations());
        evaluations.add(new Evaluation(answered, route.current().id(), decision,
                previous == null ? Probe.OPENING : previous.probe(), previous == null ? route.current().objective() : previous.intent()));
        var digest = evaluations.stream().map(e -> new DigestEntry(e.round(), e.competencyId(), e.decision().level(),
                clip(e.decision().evidence(), 120), clip(e.decision().gap(), 100), e.answeredProbe())).toList();
        return new InterviewAgentState(state.version(), state.executionMode(), state.plan(), expected, evaluations, digest, state.practiceMemory(), state.training());
    }

    /** Weak guard for clearly disjoint technical questions; metadata alone does not prove semantic alignment. */
    public void validateQuestion(String competencyId, String question) {
        if (!List.of("storage", "concurrency", "performance").contains(competencyId)) return;
        var target = new Competency(competencyId, "", "");
        if (matches(target, question) > 0) return;
        if (List.of("storage", "concurrency", "performance").stream().filter(id -> !id.equals(competencyId))
                .anyMatch(id -> matches(new Competency(id, "", ""), question) > 0))
            throw new IllegalArgumentException("questionCompetencyId 与问题技术主题不一致，请围绕 " + competencyId + " 重新提问");
    }
}
