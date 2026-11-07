package cn.edu.whu.zhizhida.learning.api;

import java.util.List;
import java.util.Optional;

/** 学习模块拥有目标与会话的关联；调用方只能传递已经授权的账号和来源标识。 */
public interface GoalSessionLinks {
    record Link(String goalId, String sessionId, String taskId, String questionId) { }

    Optional<String> goalForSession(long owner, String sessionId);
    List<Link> forGoal(long owner, String goalId);

    /** 与来源会话、训练任务及运行终态共用调用方事务，不启动独立提交。 */
    void attach(long owner, Link link);
}
