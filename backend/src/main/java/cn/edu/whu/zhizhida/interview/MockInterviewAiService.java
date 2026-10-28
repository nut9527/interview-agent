package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public final class MockInterviewAiService extends StructuredInterviewAiService {
    private final ObjectMapper mapper;

    public MockInterviewAiService(ObjectMapper mapper, BusinessMetrics metrics) {
        super(metrics);
        this.mapper = mapper;
    }

    @Override
    public String executionMode() { return "mock"; }

    @Override
    protected JsonNode generateOpening(String targetRole, String resumeSnapshot) {
        var agent = InterviewAgentContext.decode(resumeSnapshot);
        if (agent != null && agent.checkpoint() != null) return mapper.createObjectNode()
                .put("question", retryOpening(agent, agentQuestion(agent.routing().current(), agent.materials(), agent.difficulty())))
                .put("questionCompetencyId", agent.routing().current().id());
        return mapper.createObjectNode().put("question", mockOpeningQuestion(targetRole, resumeSnapshot));
    }

    private String retryOpening(InterviewAgentContext context, String question) {
        var training = context.checkpoint().training();
        if (training == null || training.retry() == null) return question;
        String opening = "围绕「" + context.routing().current().name() + "」，请补充原回答待验证项「"
                + training.retry().gap().substring(0, Math.min(100, training.retry().gap().length()))
                + "」，说明你的具体行动、依据与限制。";
        opening += switch (context.difficulty()) {
            case BEGINNER -> "可以先说明一个基础步骤。";
            case STANDARD -> "请补充实际验证依据。";
            case ADVANCED -> "请解释复杂约束、替代方案与适用边界。";
        };
        return opening.substring(0, Math.min(300, opening.length()));
    }

    @Override
    protected JsonNode generateReview(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                     int answeredCount, boolean finalRound) {
        var agent = InterviewAgentContext.decode(resumeSnapshot);
        if (agent != null && agent.checkpoint() != null) {
            var route = agent.routing();
            String answer = transcript.get(transcript.size() - 1).content();
            var action = finalRound ? InterviewAgentState.Action.FINISH
                    : answer.length() < 80 && route.allowedActions().contains(InterviewAgentState.Action.FOLLOW_UP)
                    ? InterviewAgentState.Action.FOLLOW_UP : route.allowedActions().contains(InterviewAgentState.Action.ADVANCE)
                    ? InterviewAgentState.Action.ADVANCE : InterviewAgentState.Action.FOLLOW_UP;
            var target = action == InterviewAgentState.Action.ADVANCE ? route.next() : route.current();
            var probe = action == InterviewAgentState.Action.FOLLOW_UP ? route.allowedProbes().get(0)
                    : action == InterviewAgentState.Action.ADVANCE ? InterviewAgentState.Probe.OPENING : InterviewAgentState.Probe.NONE;
            boolean repeated = agent.checkpoint().evaluations().stream().anyMatch(e -> e.competencyId().equals(route.current().id())
                    && e.decision().probe() == probe);
            String intent = action == InterviewAgentState.Action.FOLLOW_UP ? probeIntent(probe, repeated)
                    : action == InterviewAgentState.Action.ADVANCE ? "考察「" + target.name() + "」的实际应用与边界" : "汇总已考察能力的证据和待验证项";
            var decision = new InterviewAgentState.Decision(
                    answer.length() < 40 ? InterviewAgentState.Level.INSUFFICIENT : InterviewAgentState.Level.PARTIAL,
                    answer.substring(0, Math.min(160, answer.length())),
                    "需要进一步核对「" + route.current().name() + "」的事实与验证依据", action, target.id(),
                    "演示规则根据回答篇幅与剩余轮次选择" + (action == InterviewAgentState.Action.ADVANCE ? "进入下一能力点。"
                            : action == InterviewAgentState.Action.FINISH ? "结束并复盘。" : "继续补充当前能力的事实。"),
                    "围绕「" + route.current().name() + "」准备一段两分钟回答，明确个人行动、依据与限制。", probe, intent,
                    finalRound ? "" : target.id());
            return mapper.valueToTree(new InterviewAiReply("演示反馈：已收到你的回答。请结合「" + route.current().name()
                    + "」核对个人行动，补充实际验证依据；此模式不提供真实模型能力判断。",
                    finalRound ? "" : action == InterviewAgentState.Action.FOLLOW_UP
                            ? "关于「" + route.current().name() + "」：" + intent + "。"
                            : agentQuestion(target, agent.materials(), agent.difficulty()),
                    finalRound ? "本轮面试结束。演示模式已完成 " + answeredCount
                            + " 轮流程；请查看能力覆盖与逐轮原文，根据待验证项继续练习。演示评估不代表真实能力或招聘结果。" : "", decision));
        }
        if (agent != null) resumeSnapshot = agent.materials();
        if (normalizeNullable(resumeSnapshot).startsWith("本次练习项目：")) {
            String question = answeredCount == 1 ? "在这个项目中，你比较过哪些备选方案？请说明最终选择的理由和接受的代价。"
                    : "你在这个项目中如何验证方案选择有效？请说明实际验证方法、结果和限制。";
            return mapper.valueToTree(new InterviewAiReply("请核对是否讲清本项目的约束、备选方案和代价；补充实际验证依据，未做的验证保持未知。",
                    finalRound ? "" : question, finalRound ? "项目练习完成。请根据本轮问题与反馈，将仍需补充的事实整理回对应项目。" : ""));
        }
        return mapper.valueToTree(mockReview(targetRole, transcript, finalRound));
    }

    private String agentQuestion(InterviewAgentState.Competency competency, String materials, InterviewDifficulty difficulty) {
        String project = materials.startsWith("本次练习项目：")
                ? materials.lines().findFirst().orElse("") + "。" : "请结合一段真实经历或你会采用的方案，";
        var anchor = competency.materialAnchors().stream().filter(a -> a.kind() == InterviewAgentState.AnchorKind.PROJECT
                || a.kind() == InterviewAgentState.AnchorKind.INTERNSHIP).findFirst();
        String question = anchor.map(a -> "请结合材料条目「" + a.quote().substring(0, Math.min(140, a.quote().length())) + "」，").orElse(project)
                + "说明「" + competency.name() + "」：" + new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy().controlled(competency).objective() + "。";
        question += switch (difficulty) {
            case BEGINNER -> "可以先从自己的职责与一个基础步骤说起。";
            case STANDARD -> "请说明个人行动及验证依据。";
            case ADVANCED -> "请结合复杂约束解释原理、替代方案与适用边界。";
        };
        return question.substring(0, Math.min(300, question.length()));
    }

    private String probeIntent(InterviewAgentState.Probe probe, boolean repeated) {
        if (repeated) return switch (probe) {
            case EVIDENCE -> "请说明结果交付后如何保留可复核记录";
            case QUANTIFY -> "请说明测量样本、误差与结论的适用范围";
            case COUNTEREXAMPLE -> "请说明一次表面成功但未达到业务目标的情况";
            case TRANSFER -> "请说明资源减半时如何调整实施过程";
            case TRADEOFF -> "请比较短期交付与长期维护成本";
            case CLARIFY -> "请澄清关键决策的授权与责任边界";
            default -> throw new IllegalArgumentException("Not a follow-up probe");
        };
        return switch (probe) {
            case EVIDENCE -> "请提供能核对个人行动与判断依据的具体细节";
            case QUANTIFY -> "请用实际基线、指标和测量方法说明结果";
            case COUNTEREXAMPLE -> "请说明一个方案失效的反例及其边界";
            case TRANSFER -> "请说明约束变化后如何将方案迁移到新场景";
            case TRADEOFF -> "请比较备选方案并解释接受的代价";
            case CLARIFY -> "请澄清你与团队分别承担的职责和交接边界";
            default -> throw new IllegalArgumentException("Not a follow-up probe");
        };
    }

    private InterviewAiReply mockReview(String targetRole,
                                        List<InterviewTurn> transcript,
                                        boolean finalRound) {
        String latestAnswer = transcript.stream()
                .filter(turn -> "user".equals(turn.speaker()))
                .reduce((first, second) -> second)
                .map(InterviewTurn::content)
                .orElse("");
        String feedback = mockFeedback(latestAnswer);
        if (finalRound) {
            return new InterviewAiReply(feedback, "", mockFinalSummary(transcript));
        }
        return new InterviewAiReply(feedback, mockNextQuestion(targetRole, transcript), "");
    }

    private String mockOpeningQuestion(String targetRole, String resumeSnapshot) {
        if (normalizeNullable(resumeSnapshot).startsWith("本次练习项目：")) {
            String project = resumeSnapshot.lines().findFirst().orElse("").substring("本次练习项目：".length());
            return "围绕“" + project + "”，请说明你选择技术方案时面对的问题与约束，以及选择该方案的主要原因。";
        }
        if (normalizeNullable(resumeSnapshot).isBlank()
                || normalizeNullable(resumeSnapshot).endsWith("简历：")) {
            return "请用 1 分钟介绍与“" + targetRole + "”最相关的一段经历，并说明你在其中承担的具体职责。";
        }
        return "结合你提供的简历，请介绍一段最能证明你胜任“" + targetRole
                + "”的经历，并说明你的具体职责和结果。";
    }

    private String mockNextQuestion(String targetRole, List<InterviewTurn> transcript) {
        long answeredCount = transcript.stream().filter(turn -> "user".equals(turn.speaker())).count();
        if (answeredCount == 1) {
            return "在刚才的经历中，你遇到的最大难点是什么？请说明你的判断、行动和最终结果。";
        }
        return "如果你以“" + targetRole + "”身份重新完成这个任务，你会优先改进哪一点，为什么？";
    }

    private String mockFeedback(String answer) {
        List<String> notes = new ArrayList<>();
        notes.add(answer.length() < 40 ? "回答信息量偏少" : "回答的信息量较完整");
        notes.add(answer.matches(".*\\d+.*") ? "使用了量化信息" : "建议补充真实可核验的数字或结果");
        notes.add(containsAny(answer, List.of("负责", "实现", "设计", "优化", "复盘", "协作"))
                ? "能够体现个人行动"
                : "建议明确你的个人职责和关键动作");
        return String.join("；", notes) + "。";
    }

    private String mockFinalSummary(List<InterviewTurn> transcript) {
        long quantifiedAnswers = transcript.stream()
                .filter(turn -> "user".equals(turn.speaker()))
                .filter(turn -> turn.content().matches(".*\\d+.*"))
                .count();
        return "本轮面试结束。你已完成动机、经历深挖和复盘能力三个方向的回答，其中 "
                + quantifiedAnswers + " 个回答包含量化信息。建议继续使用 STAR 结构，"
                + "并准备一段可深入追问的项目细节和一项真实成果指标。";
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
