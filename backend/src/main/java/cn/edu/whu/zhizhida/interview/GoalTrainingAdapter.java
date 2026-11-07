package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.UserInputException;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.learning.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** 连接学习目标与面试训练：将已确认计划落为任务，并向目标复核提供可追溯的作答证据。 */
@Service
public class GoalTrainingAdapter implements GoalTraining {
    private final JdbcTemplate jdbc;
    private final QuestionCatalog questions;
    private final PracticeEvidenceSource evidence;
    private final GoalSessionLinks goalSessions;
    public GoalTrainingAdapter(JdbcTemplate jdbc,QuestionCatalog questions,PracticeEvidenceSource evidence,GoalSessionLinks goalSessions) {
        this.jdbc=jdbc;this.questions=questions;this.evidence=evidence;this.goalSessions=goalSessions;
    }
    public Question question(long owner,String id,Integer version) {
        var q=questions.requirePublishedForExecution(owner,id,version);
        return new Question(q.id(),q.version(),q.title(),q.competencyId());
    }
    public String apply(long owner,String goal,String plan,int revision,Item item) {
        question(owner,item.questionId(),item.questionVersion());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",Integer.class,owner)>=500)
            throw new UserInputException("训练任务已达上限，请先清理");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO interview_training_tasks(id,user_id,source_session_id,source_round,question_id,title,gap_text,source_type,goal_id,plan_id,plan_revision,plan_item_key,question_version) "
                + "VALUES(?,?,NULL,0,?,?,?,'GOAL',?,?,?,?,?)",id,owner,item.questionId(),item.title(),item.acceptance(),goal,plan,revision,item.itemKey(),item.questionVersion());
        return id;
    }
    public List<Result> results(long owner,String goal) {
        return results(owner,goal,List.of("CAUSAL_EXPLANATION","RESPONSIBILITY"));
    }
    public List<Result> results(long owner,String goal,List<String> dimensions) {
        var rows=goalSessions.forGoal(owner,goal);
        var results=new ArrayList<Result>();
        for(var row:rows) {
            String session=row.sessionId();
            var snapshot=evidence.lock(owner,session);
            var eligible=snapshot.stream().flatMap(s->s.facts().stream()).filter(f->f.executionMode().equalsIgnoreCase("deepseek")
                    && Set.of("qualitative-v1","behavioral-six-v1").contains(f.rubricVersion())
                    && f.observation()==AssessmentResult.Observation.OBSERVED
                    && f.claim().status()==AssessmentResult.ClaimStatus.SUPPORTED && !f.evidence().isEmpty()
                    && f.evidence().stream().allMatch(e->e.ownerId()==owner&&e.validity()==AssessmentResult.Validity.VALID)).toList();
            // 所需维度必须来自同一能力、同一轮次和同一评分标准，不能拼接不同回答凑齐条件。
            boolean supported=eligible.stream().anyMatch(a->dimensions.stream().allMatch(d->eligible.stream().anyMatch(b->
                    b.dimension().equals(d)&&b.competencyId().equals(a.competencyId())&&b.round()==a.round()&&b.rubricVersion().equals(a.rubricVersion()))));
            results.add(new Result(row.taskId(),session,row.questionId(),snapshot.isPresent(),supported,
                    supported?"观察满足结构条件，请核对原回答及结论；支持结论不等于回答正确":"尚无完整、有效且同标准的真实作答证据",
                    snapshot.map(s->s.source().version()).orElse(null),eligible.stream().map(f->f.dimension()).distinct().sorted().toList()));
        }
        return results;
    }
}
