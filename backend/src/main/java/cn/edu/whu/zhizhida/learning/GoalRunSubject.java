package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.common.execution.RunSubject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 目标行访问与提交、执行目标 Run 的服务解耦。 */
@Component
public final class GoalRunSubject implements RunSubject {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public GoalRunSubject(JdbcTemplate jdbc, @Value("${learning.goals.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public String subjectType() { return "GOAL"; }

    public void lock(long owner, String id) {
        if (jdbc.queryForList("SELECT id FROM learning_goals WHERE owner_id=? AND id=? FOR UPDATE", owner, id).isEmpty())
            throw new ResourceNotFoundException("目标不存在");
    }

    public void validate(long owner, String id, long revision, String operation) {
        if (!"GOAL_PLAN".equals(operation)) throw new RunFailureException("RUN_OPERATION");
        if (!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "目标新命令入口已关闭；已有记录仍可查看。");
        var rows = jdbc.query("SELECT revision,status,expires_at>CURRENT_TIMESTAMP(6) AS live "
                + "FROM learning_goals WHERE owner_id=? AND id=? FOR UPDATE",
                (r, n) -> new State(r.getLong("revision"), r.getString("status"), r.getBoolean("live")), owner, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("目标不存在");
        var state = rows.getFirst();
        if (!"ACTIVE".equals(state.status()) || !state.live())
            throw new BusinessException(HttpStatus.CONFLICT, "目标未激活或已到期");
        if (state.revision() != revision) throw new BusinessException(HttpStatus.CONFLICT, "目标修订已变化");
    }

    private record State(long revision, String status, boolean live) { }
}
