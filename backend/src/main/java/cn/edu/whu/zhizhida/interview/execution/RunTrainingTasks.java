package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.learning.api.GoalSessionLinks;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 在运行提交事务中完成训练任务与会话关联；学习表只经所属模块的端口写入。 */
final class RunTrainingTasks {
    private final JdbcTemplate jdbc;
    private final GoalSessionLinks goals;

    RunTrainingTasks(JdbcTemplate jdbc, GoalSessionLinks goals) {
        this.jdbc = jdbc;
        this.goals = goals;
    }

    void committed(long owner, String runId, String sessionId) {
        String task = jdbc.queryForObject("SELECT training_task_id FROM interview_runs WHERE id=?", String.class, runId);
        if (task == null) return;
        var links = jdbc.query("SELECT goal_id,question_id FROM interview_training_tasks "
                        + "WHERE id=? AND user_id=? AND goal_id IS NOT NULL",
                (r, n) -> new GoalSessionLinks.Link(r.getString("goal_id"), sessionId, task, r.getString("question_id")),
                task, owner);
        links.forEach(link -> goals.attach(owner, link));
        if (jdbc.update("UPDATE interview_training_tasks SET active_session_id=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND user_id=? AND active_run_id=?", sessionId, task, owner, runId) != 1)
            throw new BusinessException(HttpStatus.CONFLICT, "面试命令或回合已变更，请核对原运行结果。");
    }

    void failed(long owner, String runId) {
        jdbc.update("UPDATE interview_training_tasks SET active_run_id=NULL,status='TODO',updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE user_id=? AND active_run_id=?", owner, runId);
    }
}
