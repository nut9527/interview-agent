package cn.edu.whu.zhizhida.learning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class GoalUpdateWorker {
    private final JdbcTemplate jdbc;
    private final LearningGoalService goals;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    public GoalUpdateWorker(JdbcTemplate jdbc,LearningGoalService goals,TransactionTemplate transactions,
            @Value("${learning.worker-enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.goals=goals;this.transactions=transactions;this.enabled=enabled;
    }
    record Update(long id,long owner,String goal,String session,String version) { }
    @Scheduled(fixedDelay=2000)
    public void poll() {
        if(!enabled) return;
        var updates=jdbc.query("SELECT * FROM learning_goal_updates WHERE status='PENDING' ORDER BY id LIMIT 20",
                (r,n)->new Update(r.getLong("id"),r.getLong("owner_id"),r.getString("goal_id"),r.getString("session_id"),r.getString("source_version")));
        for(var update:updates) try { process(update);
        } catch(RuntimeException ex) {
            jdbc.update("UPDATE learning_goal_updates SET attempts=attempts+1,status=IF(attempts>=3,'FAILED','PENDING') WHERE id=? AND status='PENDING'",update.id());
            jdbc.update("UPDATE learning_goals g JOIN learning_goal_updates u ON u.goal_id=g.id SET g.coach_state='FAILED' WHERE u.id=? AND u.status='FAILED' AND g.coach_state<>'PLANNING'",update.id());
        }
    }
    void process(Update update) {
        transactions.executeWithoutResult(status->{
                var pending=jdbc.queryForList("SELECT id FROM learning_goal_updates WHERE id=? AND status='PENDING' FOR UPDATE SKIP LOCKED",Long.class,update.id());
                if(pending.isEmpty()) return;
                if(goals.replan(update.owner(),update.goal(),update.session(),update.version()))
                    jdbc.update("UPDATE learning_goal_updates SET status='DONE' WHERE id=?",update.id());
        });
    }
}
