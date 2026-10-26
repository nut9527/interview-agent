package cn.edu.whu.zhizhida.interview.domain;

import cn.edu.whu.zhizhida.interview.api.InterviewPreparation;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;
import java.util.ArrayList;
import java.util.List;

/** 保留原始问题与反馈，生成复盘提示；提示本身不表示所有回答均存在不足。 */
public final class ProjectFollowUps {
    private ProjectFollowUps() {}

    public static List<InterviewPreparation.FollowUp> from(List<InterviewTurn> turns) {
        List<InterviewPreparation.FollowUp> result = new ArrayList<>();
        String question = "";
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            if ("interviewer".equals(turn.speaker())) question = turn.content();
            if ("user".equals(turn.speaker()) && !question.isBlank()) {
                String feedback = i + 1 < turns.size() && "system".equals(turns.get(i + 1).speaker())
                        ? turns.get(i + 1).content() : "";
                result.add(new InterviewPreparation.FollowUp(result.size() + 1, question, turn.content(), feedback));
            }
        }
        return List.copyOf(result);
    }
}
