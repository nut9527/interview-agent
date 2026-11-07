package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.learning.api.GoalSessionLinks;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LearningGoalSessionLinks implements GoalSessionLinks {
    private final JdbcTemplate jdbc;

    public LearningGoalSessionLinks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<String> goalForSession(long owner, String sessionId) {
        return jdbc.queryForList("SELECT goal_id FROM learning_goal_sessions WHERE owner_id=? AND session_id=?",
                String.class, owner, sessionId).stream().findFirst();
    }

    @Override
    public List<Link> forGoal(long owner, String goalId) {
        return jdbc.query("SELECT goal_id,session_id,task_id,question_id FROM learning_goal_sessions "
                        + "WHERE owner_id=? AND goal_id=? ORDER BY session_id",
                (r, n) -> new Link(r.getString("goal_id"), r.getString("session_id"),
                        r.getString("task_id"), r.getString("question_id")), owner, goalId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(long owner, Link link) {
        jdbc.update("INSERT INTO learning_goal_sessions(owner_id,goal_id,session_id,task_id,question_id) VALUES(?,?,?,?,?)",
                owner, link.goalId(), link.sessionId(), link.taskId(), link.questionId());
    }
}
