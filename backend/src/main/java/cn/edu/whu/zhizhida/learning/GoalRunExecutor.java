package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.execution.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 将通用运行调度接入目标规划；最终提交由目标服务复核修订号及已固定的观察引用。 */
@Component
public class GoalRunExecutor implements RunExecutor {
    private final LearningGoalService goals;
    private final ObjectMapper json;
    private final GoalPlanner planner;
    public GoalRunExecutor(LearningGoalService goals,ObjectMapper json,GoalPlanner planner) { this.goals=goals;this.json=json;this.planner=planner; }
    public String subjectType() { return "GOAL"; }
    public void execute(ExecutionPort.Context context,String id,String operation,String input) {
        if(!operation.equals("GOAL_PLAN")) throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_OPERATION");
        long revision=json.readTree(input).path("expectedRevision").asLong();
        var node=json.readTree(input).path("observationRefs");
        var refs=node.isArray()?json.convertValue(node,new tools.jackson.core.type.TypeReference<java.util.List<cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Reference>>(){}):java.util.List.<cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Reference>of();
        var inputState=goals.planningInput(context.owner(),id,revision,refs);
        var draft=planner.plan(context,inputState,()->goals.requirePlanningScope(context.owner(),id,revision,refs));
        goals.commitProposal(context,id,revision,refs,draft);
    }
}
