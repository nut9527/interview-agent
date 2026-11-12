package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.common.UserInputException;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.learning.api.*;
import cn.edu.whu.zhizhida.learning.api.LearningGoal.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/**
 * 管理学习目标、计划修订和人工确认，以修订号与请求键约束并发修改和幂等重放。
 *
 * <p>运行预算按目标累计预占；计划确认后才创建训练任务，目标完成状态绑定有效证据和用户确认。
 */
@Service
public class LearningGoalService implements MissionBudget {
    public static final String CRITERION="至少在指定数量的不同题目场景完成真实作答；有效、同标准评价中同时观察到因果解释与职责证据。模型评价仅作待人工复核的训练观察，不证明专业能力达标。";
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final GoalTraining training;
    private final ObjectMapper json;
    private final ExecutionPort runs;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final GoalHistory history;
    private final boolean enabled;
    public LearningGoalService(JdbcTemplate jdbc, CurrentUserProvider users, GoalTraining training, ObjectMapper json, ExecutionPort runs,
            org.springframework.transaction.support.TransactionTemplate transactions,GoalHistory history) {
        this(jdbc,users,training,json,runs,transactions,history,true);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LearningGoalService(JdbcTemplate jdbc, CurrentUserProvider users, GoalTraining training, ObjectMapper json, ExecutionPort runs,
            org.springframework.transaction.support.TransactionTemplate transactions,GoalHistory history,
            @org.springframework.beans.factory.annotation.Value("${learning.goals.enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.users=users;this.training=training;this.json=json;this.runs=runs;this.transactions=transactions;this.history=history;
        this.enabled=enabled;
    }
    public static String hash(String text) {
        return Sha256.hex(text);
    }
    public void lock(long owner,String id) {
        if(jdbc.queryForList("SELECT id FROM learning_goals WHERE owner_id=? AND id=? FOR UPDATE",owner,id).isEmpty())
            throw new ResourceNotFoundException("目标不存在");
    }
    private LearningGoal goal(long owner,String id) {
        return jdbc.query("SELECT *,UNIX_TIMESTAMP(expires_at) AS expires_epoch FROM learning_goals WHERE owner_id=? AND id=? FOR UPDATE",(r,n)->new LearningGoal(
                r.getString("id"),r.getString("title"),r.getString("competency_id"),r.getString("completion_criterion"),
                r.getInt("required_scenarios"),json.readValue(r.getString("question_ids_json"),new TypeReference<List<String>>(){}),
                r.getString("status"),r.getLong("revision"),r.getInt("max_runs"),r.getInt("max_training"),r.getInt("max_model_attempts"),r.getInt("max_tools"),
                r.getInt("reserved_runs"),r.getInt("reserved_training"),r.getInt("reserved_model_attempts"),r.getInt("reserved_tools"),
                Instant.ofEpochSecond(r.getLong("expires_epoch")),plans(owner,id),r.getLong("criteria_revision"),
                json.readValue(r.getString("dimensions_json"),new TypeReference<List<String>>(){}),
                r.getBoolean("auto_adjust"),coachState(owner,r.getString("coach_state"),r.getString("planning_run_id")),
                planning(owner,r.getString("planning_run_id"),r.getString("planning_trigger"),r.getString("planning_source_session"))),owner,id).stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("目标不存在"));
    }
    public LearningGoal get(String id) { return display(users.requireUserId(),id); }
    /**
     * 读取目标展示状态，并重新核对已完成目标的证据。
     *
     * <p>来源或评价标准变化时，复核会将目标转为暂停，展示状态以当前证据为准。
     */
    private LearningGoal display(long owner,String id) {
        return transactions.execute(status->{
            var states=jdbc.queryForList("SELECT status FROM learning_goals WHERE owner_id=? AND id=?",String.class,owner,id);
            if(states.contains("ACHIEVED")) review(owner,id);
            return goal(owner,id);
        });
    }
    public List<LearningGoal> list() {
        long owner=users.requireUserId();
        return jdbc.queryForList("SELECT id FROM learning_goals WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 30",String.class,owner)
                .stream().map(id->display(owner,id)).toList();
    }
    @Transactional
    public LearningGoal create(Create input) {
        long owner=users.requireUserId();
        // 持有账号锁，使容量检查、目标创建与账号删除按顺序执行。
        jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",owner).stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("账号不存在"));
        String hash=hash(json.writeValueAsString(input));
        var previous=jdbc.queryForList("SELECT id,request_hash FROM learning_goals WHERE owner_id=? AND request_id=?",owner,input.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw conflict("请求键已用于另一目标");
            return goal(owner,(String)previous.getFirst().get("id"));
        }
        requireEnabled();
        if(input.questionIds().stream().distinct().count()!=input.questionIds().size()
                ||input.requiredScenarios()>input.questionIds().size()||input.maxTraining()<input.requiredScenarios())
            throw new UserInputException("完成标准需要足够的不同题目和训练额度");
        var candidates=input.questionIds().stream().map(id->training.question(owner,id,null)).toList();
        String competency=candidates.getFirst().competencyId();
        if(candidates.stream().anyMatch(q->!q.competencyId().equals(competency)))
            throw new UserInputException("首版目标仅接受同一能力的不同题目");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goals WHERE owner_id=?",Integer.class,owner)>=30)
            throw new UserInputException("最多保留 30 个目标");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learning_goals(id,owner_id,request_id,request_hash,title,competency_id,completion_criterion,required_scenarios,question_ids_json,"
                + "max_runs,max_training,max_model_attempts,max_tools,auto_adjust,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,TIMESTAMPADD(DAY,30,CURRENT_TIMESTAMP(6)))",
                id,owner,input.requestId(),hash,input.title().trim(),competency,CRITERION,input.requiredScenarios(),json.writeValueAsString(input.questionIds()),
                input.maxRuns(),input.maxTraining(),input.maxModelAttempts(),input.maxTools(),Boolean.TRUE.equals(input.autoAdjust()));
        jdbc.update("INSERT INTO learning_goal_criteria(goal_id,revision,required_scenarios,dimensions_json) SELECT id,criteria_revision,required_scenarios,dimensions_json FROM learning_goals WHERE id=?",id);
        return goal(owner,id);
    }
    private void active(LearningGoal goal) {
        if(!goal.status().equals("ACTIVE")||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at>CURRENT_TIMESTAMP(6) FROM learning_goals WHERE id=?",Boolean.class,goal.id()))) throw conflict("目标未激活或已到期");
    }
    @Transactional
    public LearningGoal change(String id,Change input) {
        long owner=users.requireUserId();lock(owner,id);var goal=goal(owner,id);
        if(goal.revision()!=input.expectedRevision()||Set.of("CANCELLED","ACHIEVED").contains(goal.status())) throw conflict("目标已变化");
        if(input.action()==Action.RESUME&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at>CURRENT_TIMESTAMP(6) FROM learning_goals WHERE id=?",Boolean.class,id))) throw conflict("目标已到期");
        if(input.action()==Action.RESUME) requireEnabled();
        String status=switch(input.action()) { case PAUSE->"PAUSED";case RESUME->"ACTIVE";case CANCEL->"CANCELLED"; };
        jdbc.update("UPDATE learning_goals SET status=?,revision=revision+1 WHERE id=?",status,id);
        // 已获准执行的计算保留预算扣减；目标修订号用于阻止旧任务提交结果。
        return goal(owner,id);
    }
    @Transactional
    public ExecutionPort.Run propose(String id,Command input) {
        long owner=users.requireUserId();
        var scope=jdbc.queryForList("SELECT competency_id FROM learning_goals WHERE owner_id=? AND id=?",String.class,owner,id);
        if(scope.isEmpty()) throw new ResourceNotFoundException("目标不存在");
        // 观察来源先于目标加锁，与计划提交和回答提交的锁顺序保持一致。
        var refs=history.freeze(owner,scope.getFirst(),input.observationIds());
        lock(owner,id);
        return acceptPlan(owner,id,input.expectedRevision(),input.requestId(),refs,"USER_REQUEST",null);
    }
    private ExecutionPort.Run acceptPlan(long owner,String id,long revision,String requestId,
            List<PracticeMemoryAccess.Reference> refs,String trigger,String session) {
        var run=runs.accept(new ExecutionPort.Command(owner,"GOAL",id,revision,"GOAL_PLAN",requestId,
                json.writeValueAsString(Map.of("expectedRevision",revision,"goalId",id,"observationRefs",refs))));
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED","EXPIRED").contains(run.status()))
            jdbc.update("UPDATE learning_goals SET planning_run_id=?,planning_trigger=?,planning_source_session=?,coach_state='PLANNING' WHERE id=?",
                    run.id(),trigger,session,id);
        return run;
    }
    private Planning planning(long owner,String run,String trigger,String session) {
        if(run==null) return null;
        var current=runs.read(owner,run);
        return new Planning(run,current.status(),trigger,session,current.error());
    }
    private String coachState(long owner,String state,String run) {
        if(!state.equals("PLANNING")||run==null) return state;
        return Set.of("FAILED","CANCELLED","EXPIRED").contains(runs.read(owner,run).status())?"FAILED":state;
    }
    @Transactional
    public GoalPlanner.Input planningInput(long owner,String id,long revision,List<PracticeMemoryAccess.Reference> refs) {
        var observations=history.qualify(owner,refs);
        var results=training.results(owner,id);
        var goal=goal(owner,id);active(goal);
        if(goal.revision()!=revision) throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_GOAL_CHANGED");
        var available=observations.stream().map(LearningMemory.Observation::id).collect(java.util.stream.Collectors.toSet());
        var previous=goal.plans().isEmpty()?List.<Focus>of():goal.plans().getFirst().focus().stream()
                .filter(f->available.containsAll(f.observationIds())).toList();
        return new GoalPlanner.Input(goal,goal.questionIds().stream().map(q->training.question(owner,q,null)).toList(),observations,results,previous);
    }
    @Transactional
    public void requirePlanningScope(long owner,String id,long revision,List<PracticeMemoryAccess.Reference> refs) {
        history.qualify(owner,refs);
        var goal=goal(owner,id);active(goal);
        if(goal.revision()!=revision) throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_GOAL_CHANGED");
    }

    @Transactional
    public boolean replan(long owner,String id,String session,String version) {
        var results=training.results(owner,id);
        if(results.stream().noneMatch(r->r.sessionId().equals(session)&&r.completed()&&Objects.equals(r.sourceVersion(),version))) return true;
        var scope=jdbc.queryForObject("SELECT competency_id FROM learning_goals WHERE owner_id=? AND id=?",String.class,owner,id);
        var sessions=results.stream().map(GoalTraining.Result::sessionId).collect(java.util.stream.Collectors.toSet());
        var refs=history.forSessions(owner,scope,sessions);
        var review=review(owner,id);var goal=goal(owner,id);
        if(!goal.autoAdjust()||!goal.status().equals("ACTIVE")||goal.expiresAt().isBefore(Instant.now())) return true;
        String evidenceHash=hash(goal.revision()+"\n"+json.writeValueAsString(results.stream().filter(GoalTraining.Result::completed)
                .sorted(Comparator.comparing(GoalTraining.Result::taskId)).map(r->List.of(r.taskId(),r.sourceVersion())).toList()));
        if(evidenceHash.equals(jdbc.queryForObject("SELECT last_auto_evidence_hash FROM learning_goals WHERE id=?",String.class,id))) return true;
        if(goal.coachState().equals("PLANNING")) return false;
        if(review.outcome().equals("AWAITING_HUMAN_REVIEW")) {
            jdbc.update("UPDATE learning_goals SET coach_state='AWAITING_HUMAN_REVIEW' WHERE id=?",id);
            return true;
        }
        var completed=results.stream().filter(GoalTraining.Result::completed).map(GoalTraining.Result::taskId)
                .collect(java.util.stream.Collectors.toSet());
        var applied=goal.plans().stream().filter(p->p.status().equals("APPLIED")).findFirst();
        if(applied.isPresent()&&!completed.containsAll(applied.get().taskIds())) {
            jdbc.update("UPDATE learning_goals SET coach_state='WAITING_PRACTICE' WHERE id=?",id);
            return true;
        }
        if(goal.reservedRuns()>=goal.maxRuns()||goal.reservedTraining()>=goal.maxTraining()
                ||goal.reservedModelAttempts()+3>goal.maxModelAttempts()||goal.reservedTools()+3>goal.maxTools()) {
            jdbc.update("UPDATE learning_goals SET coach_state='BUDGET_EXHAUSTED' WHERE id=?",id);
            return true;
        }
        acceptPlan(owner,id,goal.revision(),"goal-auto-"+evidenceHash,refs,"TRAINING_COMPLETED",session);
        jdbc.update("UPDATE learning_goals SET last_auto_evidence_hash=? WHERE id=?",evidenceHash,id);
        return true;
    }

    /**
     * 在目标锁内一次性预占运行、训练、模型调用和工具预算。
     *
     * <p>同一运行编号的重放复用已有预占；预算随目标累计，失败或结果未知的调用保留预占额度。
     */
    @Override
    @Transactional
    public void reserve(long owner,String goalId,String runId,boolean isTraining,int attempts,int tools) {
        lock(owner,goalId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal_runs WHERE run_id=? AND goal_id=?",Integer.class,runId,goalId)>0) return;
        requireEnabled();
        active(goal(owner,goalId));
        int changed=jdbc.update("UPDATE learning_goals SET reserved_runs=reserved_runs+1,reserved_training=reserved_training+?,"
                + "reserved_model_attempts=reserved_model_attempts+?,reserved_tools=reserved_tools+? WHERE id=? AND owner_id=? "
                + "AND reserved_runs<max_runs AND reserved_training+?<=max_training AND reserved_model_attempts+?<=max_model_attempts AND reserved_tools+?<=max_tools",
                isTraining?1:0,attempts,tools,goalId,owner,isTraining?1:0,attempts,tools);
        if(changed!=1) throw conflict("目标总预算不足；失败和未知调用保留预占额度");
        jdbc.update("INSERT INTO learning_goal_runs(goal_id,run_id,training,model_attempts,tools) VALUES(?,?,?,?,?)",goalId,runId,isTraining,attempts,tools);
    }
    /**
     * 重新校验观察来源，在运行完成事务中写入待确认计划。
     *
     * <p>来源资格、运行归属和目标修订号共同约束提交，计划写入与运行终态同步生效。
     */
    @Transactional
    public void commitProposal(ExecutionPort.Context context,String id,long revision,List<PracticeMemoryAccess.Reference> refs,Draft draft) {
        history.qualify(context.owner(),refs);
        runs.complete(context,()->proposed(context.owner(),id,revision,refs,draft));
    }
    private void proposed(long owner,String id,long revision,List<PracticeMemoryAccess.Reference> refs,Draft draft) {
        lock(owner,id);var goal=goal(owner,id);active(goal);
        if(goal.revision()!=revision) throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_GOAL_CHANGED");
        String encoded=validate(owner,goal,draft.items()),plan=UUID.randomUUID().toString();
        jdbc.update("UPDATE learning_plan_revisions r JOIN learning_plans p ON p.id=r.plan_id AND p.revision=r.revision SET r.status='SUPERSEDED' WHERE p.owner_id=? AND p.goal_id=? AND p.status='PENDING_CONFIRMATION'",owner,id);
        jdbc.update("UPDATE learning_plans SET status='SUPERSEDED' WHERE owner_id=? AND goal_id=? AND status='PENDING_CONFIRMATION'",owner,id);
        jdbc.update("INSERT INTO learning_plans(id,owner_id,goal_id,content_hash,items_json,limitation,observation_refs_json,focus_json,expires_at) SELECT ?,?,?,?,?,?,?,?,LEAST(TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)),expires_at) FROM learning_goals WHERE id=?",
                plan,owner,id,hash(encoded),encoded,draft.reason(),json.writeValueAsString(refs),json.writeValueAsString(draft.focus()),id);
        history(plan,1,"PENDING_CONFIRMATION",encoded);
        jdbc.update("UPDATE learning_goals SET coach_state='PENDING_CONFIRMATION' WHERE id=?",id);
    }
    private String validate(long owner,LearningGoal goal,List<GoalTraining.Item> items) {
        if(items.isEmpty()||items.size()>3||items.stream().map(GoalTraining.Item::itemKey).distinct().count()!=items.size()
                ||items.stream().map(GoalTraining.Item::questionId).distinct().count()!=items.size()) throw new UserInputException("计划需要 1–3 个不同题目和唯一条目标识");
        for(var item:items) {
            if(!goal.questionIds().contains(item.questionId())) throw new UserInputException("题目超出已确认目标范围");
            var q=training.question(owner,item.questionId(),item.questionVersion());
            if(!q.competencyId().equals(goal.competencyId())) throw conflict("题目能力已变更");
        }
        return json.writeValueAsString(items);
    }
    private List<Plan> plans(long owner,String goal) {
        return jdbc.query("SELECT *,UNIX_TIMESTAMP(expires_at) AS expires_epoch,expires_at<=CURRENT_TIMESTAMP(6) AS expired FROM learning_plans WHERE owner_id=? AND goal_id=? ORDER BY created_at DESC,id DESC LIMIT 20",this::planRow,owner,goal);
    }
    private Plan planRow(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Plan(
                r.getString("id"),r.getString("goal_id"),r.getInt("revision"),
                r.getString("status").equals("PENDING_CONFIRMATION")&&r.getBoolean("expired")?"EXPIRED":r.getString("status"),
                r.getString("content_hash"),json.readValue(r.getString("items_json"),new TypeReference<List<GoalTraining.Item>>(){}),
                r.getString("limitation"),Instant.ofEpochSecond(r.getLong("expires_epoch")),jdbc.queryForList("SELECT task_id FROM learning_plan_applications WHERE owner_id=? AND plan_id=? ORDER BY item_key FOR SHARE",String.class,r.getLong("owner_id"),r.getString("id")),
                refs(r.getString("observation_refs_json")),r.getString("focus_json")==null?List.of():json.readValue(r.getString("focus_json"),new TypeReference<List<Focus>>(){}));
    }
    private List<PracticeMemoryAccess.Reference> refs(String value) { return value==null?List.of():json.readValue(value,new TypeReference<List<PracticeMemoryAccess.Reference>>(){}); }
    private Plan plan(long owner,String id) {
        var ids=jdbc.queryForList("SELECT goal_id FROM learning_plans WHERE owner_id=? AND id=?",String.class,owner,id);
        if(ids.isEmpty()) throw new ResourceNotFoundException("计划不存在");
        lock(owner,ids.getFirst());
        return jdbc.query("SELECT *,UNIX_TIMESTAMP(expires_at) AS expires_epoch,expires_at<=CURRENT_TIMESTAMP(6) AS expired FROM learning_plans WHERE owner_id=? AND id=? FOR UPDATE",this::planRow,owner,id).getFirst();
    }
    private void pending(Plan plan,int revision) {
        if(plan.revision()!=revision||!plan.status().equals("PENDING_CONFIRMATION")) throw conflict("计划已变化、拒绝、被替代或到期");
    }
    private void history(String id,int revision,String status,String encoded) {
        jdbc.update("INSERT INTO learning_plan_revisions(plan_id,revision,status,content_hash,items_json) VALUES(?,?,?,?,?)",id,revision,status,hash(encoded),encoded);
    }
    @Transactional
    public Plan edit(String id,Edit input) {
        requireEnabled();
        long owner=users.requireUserId();var plan=plan(owner,id);pending(plan,input.expectedRevision());var goal=goal(owner,plan.goalId());active(goal);
        String encoded=validate(owner,goal,input.items());
        jdbc.update("UPDATE learning_plan_revisions SET status='SUPERSEDED' WHERE plan_id=? AND revision=?",id,plan.revision());
        jdbc.update("UPDATE learning_plans SET revision=revision+1,items_json=?,content_hash=? WHERE id=?",encoded,hash(encoded),id);
        history(id,plan.revision()+1,"PENDING_CONFIRMATION",encoded);
        return plan(owner,id);
    }
    @Transactional
    public Plan reject(String id,Revision input) {
        long owner=users.requireUserId();var plan=plan(owner,id);pending(plan,input.expectedRevision());
        jdbc.update("UPDATE learning_plans SET status='REJECTED' WHERE id=?",id);
        jdbc.update("UPDATE learning_plan_revisions SET status='REJECTED' WHERE plan_id=? AND revision=?",id,plan.revision());
        return plan(owner,id);
    }
    /**
     * 将用户确认的计划修订原子地转换为训练任务。
     *
     * <p>请求键绑定计划和修订号；确认前复核观察来源及内容哈希，已应用计划的重放返回原任务关联。
     */
    @Transactional
    public Plan confirm(String id,Confirm input) {
        long owner=users.requireUserId();
        var sources=jdbc.queryForList("SELECT observation_refs_json FROM learning_plans WHERE owner_id=? AND id=? AND status='PENDING_CONFIRMATION'",String.class,owner,id);
        if(!sources.isEmpty()) history.qualify(owner,refs(sources.getFirst()));
        var plan=plan(owner,id);
        if(plan.revision()!=input.expectedRevision()) throw conflict("请重新审阅当前修订");
        jdbc.update("INSERT INTO learning_plan_commands(owner_id,request_id,plan_id,revision) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",owner,input.requestId(),id,input.expectedRevision());
        var command=jdbc.queryForMap("SELECT plan_id,revision FROM learning_plan_commands WHERE owner_id=? AND request_id=? FOR UPDATE",owner,input.requestId());
        if(!id.equals(command.get("plan_id"))||((Number)command.get("revision")).intValue()!=input.expectedRevision()) throw conflict("确认请求键已用于另一修订");
        if(plan.status().equals("APPLIED")) return plan;
        requireEnabled();
        pending(plan,input.expectedRevision());var goal=goal(owner,plan.goalId());active(goal);
        String encoded=validate(owner,goal,plan.items());
        if(!hash(encoded).equals(plan.contentHash())) throw conflict("计划内容校验失败");
        for(var item:plan.items()) {
            String task=training.apply(owner,goal.id(),id,plan.revision(),item);
            jdbc.update("INSERT INTO learning_plan_applications(owner_id,plan_id,revision,item_key,task_id) VALUES(?,?,?,?,?)",owner,id,plan.revision(),item.itemKey(),task);
        }
        jdbc.update("UPDATE learning_plans SET status='APPLIED' WHERE id=?",id);
        jdbc.update("UPDATE learning_plan_revisions SET status='APPLIED' WHERE plan_id=? AND revision=?",id,plan.revision());
        jdbc.update("UPDATE learning_goals SET coach_state='WAITING_PRACTICE' WHERE id=?",goal.id());
        return plan(owner,id);
    }
    @Transactional
    public Review review(String id) {
        long owner=users.requireUserId();
        return review(owner,id);
    }
    /**
     * 按不同题目统计有效训练场景，并生成绑定评价标准和训练结果的证据摘要。
     *
     * <p>数量达标进入人工复核；已完成目标的证据摘要发生变化时，保留历史确认并暂停当前目标。
     */
    private Review review(long owner,String id) {
        // 先读取待校验的标准，再按来源、目标的顺序加锁，与回答提交的锁顺序保持一致。
        var encoded=jdbc.queryForList("SELECT dimensions_json FROM learning_goals WHERE owner_id=? AND id=?",String.class,owner,id);
        if(encoded.isEmpty()) throw new ResourceNotFoundException("目标不存在");
        List<String> dimensions=json.readValue(encoded.getFirst(),new TypeReference<List<String>>(){});
        var results=training.results(owner,id,dimensions);lock(owner,id);var goal=goal(owner,id);
        if(!dimensions.equals(goal.dimensions())) throw conflict("标准已变化，请重新复核");
        // 同一道题的多次复练只计一个场景，避免重复训练扩大完成进度。
        int scenarios=(int)results.stream().filter(r->r.completed()&&r.supported()).map(GoalTraining.Result::questionId).distinct().count();
        // 摘要同时绑定评价标准和训练结果，任一证据变化都会使旧完成确认失效。
        String digest=hash(json.writeValueAsString(List.of(goal.criteriaRevision(),goal.requiredScenarios(),dimensions,results)));
        String outcome=scenarios>=goal.requiredScenarios()?"AWAITING_HUMAN_REVIEW":"INSUFFICIENT_EVIDENCE";
        if(goal.status().equals("ACHIEVED")) {
            var previous=jdbc.queryForList("SELECT evidence_hash FROM learning_goal_completions WHERE goal_id=? ORDER BY goal_revision DESC LIMIT 1 FOR SHARE",String.class,id);
            if(!previous.isEmpty()&&digest.equals(previous.getFirst())) outcome="USER_CONFIRMED";
            else {
                // 保留历史完成确认；证据撤销后，当前目标不能继续标记为已完成。
                jdbc.update("UPDATE learning_goals SET status='PAUSED',revision=revision+1 WHERE id=?",id);
                goal=goal(owner,id);outcome="EVIDENCE_CHANGED";
            }
        }
        return new Review(outcome,scenarios,goal.requiredScenarios(),results,
                "不同题号和结构通过不证明泛化。请逐场核对原回答、反例和结论；本人确认只记录训练目标完成，不替代独立专业验收。Mock、未知模式、失效来源不计入。",
                digest,goal.revision(),goal.criteriaRevision(),dimensions);
    }
    /**
     * 在训练计划应用前更新完成标准，并使待确认草稿失效。
     *
     * <p>标准独立保存修订记录；已生成训练任务的目标沿用原标准，保证训练与复核采用同一口径。
     */
    @Transactional
    public LearningGoal criteria(String id,Criteria input) {
        requireEnabled();long owner=users.requireUserId();lock(owner,id);var goal=goal(owner,id);active(goal);
        if(goal.revision()!=input.expectedRevision()) throw conflict("目标修订已变化");
        var allowed=Set.of("EVIDENCE_SUFFICIENCY","TECHNICAL_CORRECTNESS","CAUSAL_EXPLANATION","TRADEOFFS","RESPONSIBILITY","EXPRESSION");
        var dimensions=input.dimensions().stream().distinct().sorted().toList();
        if(!Boolean.TRUE.equals(input.confirmed())||dimensions.size()!=input.dimensions().size()||!allowed.containsAll(dimensions)
                ||input.requiredScenarios()>goal.questionIds().size()||input.requiredScenarios()>goal.maxTraining())
            throw new UserInputException("请确认可执行的场景数量和不重复的评价维度");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM learning_plans WHERE goal_id=? AND status='APPLIED'",Integer.class,id)>0)
            throw conflict("已有确认训练任务，不能追溯更改其标准；请建立新目标");
        String content=json.writeValueAsString(dimensions);
        jdbc.update("UPDATE learning_goals SET required_scenarios=?,dimensions_json=?,criteria_revision=criteria_revision+1,revision=revision+1,completion_criterion=? WHERE id=?",
                input.requiredScenarios(),content,"在至少 "+input.requiredScenarios()+" 个不同题目场景核对已确认维度："+String.join("、",dimensions)+"。完成须本人逐场复核，不代表专业能力认证。",id);
        jdbc.update("INSERT INTO learning_goal_criteria(goal_id,revision,required_scenarios,dimensions_json) SELECT id,criteria_revision,required_scenarios,dimensions_json FROM learning_goals WHERE id=?",id);
        jdbc.update("UPDATE learning_plan_revisions r JOIN learning_plans p ON p.id=r.plan_id AND p.revision=r.revision SET r.status='SUPERSEDED' WHERE p.goal_id=? AND p.status='PENDING_CONFIRMATION'",id);
        jdbc.update("UPDATE learning_plans SET status='SUPERSEDED' WHERE goal_id=? AND status='PENDING_CONFIRMATION'",id);
        return goal(owner,id);
    }
    @Transactional
    public LearningGoal complete(String id,Complete input) {
        long owner=users.requireUserId();
        // 幂等重放也要重新校验证据，防止已删除或变化的来源恢复目标的完成状态。
        var review=review(owner,id);var goal=goal(owner,id);
        String requestHash=hash(id+"\n"+json.writeValueAsString(input));
        var replay=jdbc.queryForList("SELECT request_hash FROM learning_goal_completions WHERE owner_id=? AND request_id=? FOR UPDATE",String.class,owner,input.requestId());
        if(!replay.isEmpty()&&!requestHash.equals(replay.getFirst())) throw conflict("请求键已用于另一完成确认");
        if(!input.evidenceHash().equals(review.evidenceHash())) throw conflict("作答来源或标准已变化，请重新核对");
        if(goal.status().equals("ACHIEVED")&&review.outcome().equals("USER_CONFIRMED")) return goal;
        requireEnabled();active(goal);
        if(goal.revision()!=input.expectedRevision()||!Boolean.TRUE.equals(input.confirmed())||!review.outcome().equals("AWAITING_HUMAN_REVIEW"))
            throw conflict("目标修订变化或有效证据不足，不能确认完成");
        jdbc.update("INSERT INTO learning_goal_completions(goal_id,owner_id,request_id,request_hash,goal_revision,criteria_revision,evidence_hash,review_note) VALUES(?,?,?,?,?,?,?,?)",
                id,owner,input.requestId(),requestHash,goal.revision(),goal.criteriaRevision(),review.evidenceHash(),input.reviewNote().trim());
        jdbc.update("UPDATE learning_goals SET status='ACHIEVED',revision=revision+1 WHERE id=?",id);
        return goal(owner,id);
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
    private void requireEnabled() { if(!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"目标新命令入口已关闭；已有记录仍可查看。"); }
}
