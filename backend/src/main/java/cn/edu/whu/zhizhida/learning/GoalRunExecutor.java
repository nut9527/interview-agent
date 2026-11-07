package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.execution.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 将通用运行调度接入目标规划；最终提交由目标服务复核修订号及已固定的观察引用。 */
@Component
public class GoalRunExecutor implements RunExecutor {
    private final LearningGoalService goals;
    private final ObjectMapper json;
    public GoalRunExecutor(LearningGoalService goals,ObjectMapper json) { this.goals=goals;this.json=json; }
    public String subjectType() { return "GOAL"; }
    public void execute(ExecutionPort.Context context,String id,String operation,String input) {
        if(!operation.equals("GOAL_PLAN")) throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_OPERATION");
        long revision=json.readTree(input).path("expectedRevision").asLong();
        var node=json.readTree(input).path("observationRefs");
        var refs=node.isArray()?json.convertValue(node,new tools.jackson.core.type.TypeReference<java.util.List<cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Reference>>(){}):java.util.List.<cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Reference>of();
        goals.commitProposal(context,id,revision,refs);
    }
}
