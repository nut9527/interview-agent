package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.UserInputException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import cn.edu.whu.zhizhida.learning.api.MissionBudget;
import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunService;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;

/** 将训练任务与持久运行原子绑定，重复启动复用已有运行，目标训练同时预占累计预算。 */
@Service
public class TrainingTaskRunService {
    private final TrainingTaskService tasks;
    private final InterviewRunService runs;
    private final InterviewRunStore store;
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final MissionBudget budget;
    private final TransactionTemplate transaction;

    public TrainingTaskRunService(TrainingTaskService tasks, InterviewRunService runs, InterviewRunStore store,
            JdbcTemplate jdbc, CurrentUserProvider users, MissionBudget budget, TransactionTemplate transaction) {
        this.tasks = tasks;
        this.runs = runs;
        this.store = store;
        this.jdbc = jdbc;
        this.users = users;
        this.budget = budget;
        this.transaction = transaction;
    }

    public InterviewRun start(String id, InterviewRun.TaskStart command) {
        // 回放也保留原来的来源与任务锁检查；锁内只读，尚不创建新命令。
        String requestKey = "task-" + InterviewRunStore.sha(id + "\n" + command.requestId());
        var replay = transaction.execute(s -> startLocked(id, requestKey, false));
        if(replay!=null) return replay;
        // 资料版本资格检查要求事务；构造准入摘要后先释放锁，再访问 Redis。
        var task = transaction.execute(s -> tasks.find(id));
        var request = transaction.execute(s -> tasks.startRequest(task));
        runs.admitStart(new InterviewRun.Start(requestKey, request), task.goalId() == null ? null : task.gap());
        return transaction.execute(s -> startLocked(id, requestKey, true));
    }

    private InterviewRun startLocked(String id, String requestKey, boolean admitted) {
        long owner = users.requireUserId();
        var initial=tasks.find(id);
        tasks.lockDirectSource(initial);
        if(initial.goalId()!=null) budget.lock(owner,initial.goalId());
        var locked = jdbc.queryForList(
                "SELECT active_run_id FROM interview_training_tasks WHERE user_id=? AND id=? FOR UPDATE", owner, id);
        var task = tasks.find(id);
        if ("PAUSED".equals(task.status()))
            throw new UserInputException("请先继续此任务。");
        String previous = (String) locked.getFirst().get("active_run_id");
        if (previous != null) {
            var row = store.get(owner, previous);
            if (!row.terminal() || row.status().equals("SUCCEEDED"))
                return row.view();
        }
        if (task.activeSessionId() != null)
            throw new UserInputException("此任务已有会话，请继续原会话。");
        if(!admitted) return null;
        var request = tasks.startRequest(task);
        var run = runs.acceptStart(
                new InterviewRun.Start(requestKey, request), task.goalId() == null ? null : task.gap());
        if (java.util.Set.of("FAILED", "CANCELLED", "EXPIRED").contains(run.status()))
            return run;
        if(task.goalId()!=null) {
            var limits = store.executionBudget(owner, run.runId());
            budget.reserve(owner,task.goalId(),run.runId(),true,limits.maxModelAttempts(),limits.maxToolExecutions());
        }
        jdbc.update(
                "UPDATE interview_runs SET training_task_id=? WHERE id=? AND owner_id=? AND training_task_id IS NULL",
                id, run.runId(), owner);
        jdbc.update("UPDATE interview_training_tasks SET active_run_id=?,status='STARTING' WHERE user_id=? AND id=?",
                run.runId(), owner, id);
        return run;
    }
}
