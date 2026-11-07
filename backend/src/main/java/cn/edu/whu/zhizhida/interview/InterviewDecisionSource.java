package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 持久命令取得一个决策；本地演示与远程传输返回相同的消息契约。 */
interface InterviewDecisionSource {
    InterviewRunModel.Reply decide(Supplier<InterviewRunModel.Reply> transport, InterviewSession session,
            PendingAnswer pending, String context, String phase);

    static InterviewDecisionSource local(MockInterviewAiService mock, ObjectMapper json, JsonNode missingUsage) {
        return (transport, session, pending, context, phase) -> {
            JsonNode output;
            if ("ACTION".equals(phase)) {
                output = json.createObjectNode().put("version", InterviewDecisionContract.VERSION)
                        .put("phase", "ACTION").put("action", "COMPLETE").put("reason", "已有材料足以生成本轮结果");
            } else {
                output = pending == null ? mock.generateOpening(session.targetRole(), context)
                        : mock.generateReview(session.targetRole(), context, pending.turns(), pending.answeredCount(), pending.finalRound());
                if ("FINAL".equals(phase))
                    output = json.createObjectNode().put("version", InterviewDecisionContract.VERSION)
                            .put("phase", "FINAL").set("result", output);
            }
            var message = json.createObjectNode().put("role", "assistant").put("content", output.toString());
            return new InterviewRunModel.Reply(message, "stop", missingUsage.deepCopy());
        };
    }
}
