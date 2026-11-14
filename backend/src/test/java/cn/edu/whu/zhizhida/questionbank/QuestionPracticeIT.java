package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.api.QuestionSnapshot;
import cn.edu.whu.zhizhida.questionbank.domain.QuestionContentRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuestionPracticeIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    private static final String BASE="/api/interviews/sessions";
    private String user() throws Exception { return registerUniqueUserToken("password123","题库练习"); }
    private JsonNode data(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    private JsonNode start(String id, String token, int rounds) throws Exception {
        return data(startInterview(Map.of("targetRole","客户端标题","questionId",id,"totalRounds",rounds,"difficulty","ADVANCED"),token));
    }
    private JsonNode finish(JsonNode session, String token) throws Exception {
        String id=session.path("sessionId").asText();
        for(int n=1; n<=session.path("totalRounds").asInt(); n++)
            session=data(answerInterview(id, Map.of("answer","我会先验证约束再用并发实验核对失败边界。","expectedTurn",n,"requestId",id+"-question-"+n),token));
        return session;
    }
    @Test void selectedQuestionHasExactOpeningThenFollowupsAndOwnerOnlyHistory() throws Exception {
        String token=user(), id="qb-database-001";
        var question=data(performGet("/api/questions/"+id,token));
        var session=start(id,token,3);
        String sid=session.path("sessionId").asText();
        assertThat(session.path("targetRole")).isEqualTo(question.path("title"));
        assertThat(session.path("turns").get(1).path("content")).isEqualTo(question.path("question"));
        assertThat(session.path("agent").path("training").path("question").path("version").asInt()).isEqualTo(2);
        assertThat(data(performGet("/api/questions/practice-status",token)).isEmpty()).isTrue();
        var finished=finish(session,token);
        assertThat(finished.path("state").asText()).isEqualTo("FINISHED");
        assertThat(finished.path("agent").path("evaluations").size()).isEqualTo(3);
        var replay=data(answerInterview(sid, Map.of("answer","我会先验证约束再用并发实验核对失败边界。","expectedTurn",3,"requestId",sid+"-question-3"),token));
        assertThat(replay).isEqualTo(finished);
        var history=data(performGet("/api/questions/"+id+"/practices",token));
        assertThat(history.path("total").asInt()).isEqualTo(1);
        var record=history.path("items").get(0);
        assertThat(record.path("rounds").size()).isEqualTo(3);
        assertThat(record.path("rounds").get(0).path("question")).isEqualTo(question.path("question"));
        assertThat(record.path("rounds").get(0).path("feedback").asText()).contains("即时反馈");
        assertThat(record.path("rounds").get(0).path("answer").asText()).contains("并发实验");
        assertThat(record.path("createdAt").asText()).isNotBlank();
        assertThat(data(performGet("/api/questions/practice-status",token)).get(0).path("status").asText()).isEqualTo("NEEDS_REVIEW");
        String other=user();
        assertThat(data(performGet("/api/questions/"+id+"/practices",other)).path("total").asInt()).isZero();
        assertThat(performGet(BASE+"/"+sid,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(startInterview(Map.of("targetRole","复练","retrySessionId",sid,"retryRound",3,"totalRounds",3),other).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet("/api/questions/"+id+"/practices?page=2&size=1",token)).path("items").isEmpty()).isTrue();
        for(String path:List.of("/api/questions/practice-status","/api/questions/draw","/api/questions/"+id+"/practices"))
            assertThat(performGet(path).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void sameQuestionRetriesPreserveVersionAcrossRevisionAndUnpublish() throws Exception {
        String token=user(), id="qb-java-001";
        var original=finish(start(id,token,3),token);
        String sid=original.path("sessionId").asText();
        var snapshot=original.path("agent").path("training").path("question");
        int version=snapshot.path("version").asInt();
        try {
            jdbc.update("UPDATE question_bank SET version=version+1, title=? WHERE id=?","修订后的当前标题",id);
            var newer=objectMapper.readTree(snapshot.toString()).deepCopy();
            ((tools.jackson.databind.node.ObjectNode)newer).put("version",version+1).put("title","修订后的当前标题").put("question","修订后的完整题干");
            jdbc.update("INSERT INTO question_bank_versions(question_id,version,content_json,change_note) VALUES(?,?,?,?)",
                    id,version+1,newer.toString(),"测试隔离修订");
            var retry=data(startInterview(Map.of("targetRole","不可信标题","retrySessionId",sid,"retryRound",3,"totalRounds",6),token));
            assertThat(retry.path("agent").path("training").path("question")).isEqualTo(snapshot);
            assertThat(retry.path("turns").get(1).path("content")).isEqualTo(snapshot.path("question"));
            assertThat(finish(retry,token).path("state").asText()).isEqualTo("FINISHED");
            var history=data(performGet("/api/questions/"+id+"/practices",token));
            assertThat(history.path("total").asInt()).isEqualTo(2);
            assertThat(history.path("items").get(0).path("question")).isEqualTo(snapshot);
            assertThat(startInterview(Map.of("targetRole","练习","questionId",id,"questionVersion",version,"totalRounds",3),token)
                    .getResponse().getStatus()).isEqualTo(409);
            jdbc.update("UPDATE question_bank SET published=FALSE WHERE id=?",id);
            assertThat(performGet("/api/questions/"+id,token).getResponse().getStatus()).isEqualTo(404);
            assertThat(startInterview(Map.of("targetRole","练习","questionId",id,"totalRounds",3),token).getResponse().getStatus()).isEqualTo(404);
            assertThat(startInterview(Map.of("targetRole","复练","retrySessionId",sid,"retryRound",3,"totalRounds",3),token).getResponse().getStatus()).isEqualTo(404);
            assertThat(data(performGet("/api/questions/"+id+"/practices",token)).path("total").asInt()).isEqualTo(2);
            assertThat(data(performGet(BASE+"/"+sid,token)).path("agent").path("training").path("question")).isEqualTo(snapshot);
            assertThat(data(performGet("/api/questions/draw?topic=JAVA&count=10",token)).path("items"))
                    .noneMatch(row -> row.path("id").asText().equals(id));
        } finally {
            jdbc.update("UPDATE question_bank SET version=?,title=?,published=TRUE WHERE id=?",version,snapshot.path("title").asText(),id);
            jdbc.update("DELETE FROM question_bank_versions WHERE question_id=? AND version=?",id,version+1);
        }
    }
    @Test void drawingPrioritizesWeakThenUnpracticedThenPracticedWithoutDuplicates() throws Exception {
        String token=user();
        var weak=finish(start("qb-java-001",token,3),token);
        var strong=finish(start("qb-java-002",token,3),token);
        jdbc.update("UPDATE interview_sessions SET agent_json=JSON_SET(agent_json,'$.evaluations[2].decision.level','STRONG') WHERE session_id=?",
                strong.path("sessionId").asText());
        var draw=data(performGet("/api/questions/draw?topic=JAVA&count=10&roundsPerQuestion=4",token));
        assertThat(draw.path("items").size()).isEqualTo(10);
        assertThat(draw.path("roundsPerQuestion").asInt()).isEqualTo(4);
        assertThat(draw.path("items").get(0).path("id").asText()).isEqualTo("qb-java-001");
        assertThat(draw.path("items").get(9).path("id").asText()).isEqualTo("qb-java-002");
        Set<String> ids=new HashSet<>();
        draw.path("items").forEach(row -> { assertThat(ids.add(row.path("id").asText())).isTrue(); assertThat(row.path("topic").asText()).isEqualTo("JAVA"); });
        var filtered=data(performGet("/api/questions/draw?topic=JAVA&difficulty=ADVANCED&count=5",token));
        assertThat(filtered.path("items").size()).isEqualTo(2);
        filtered.path("items").forEach(row -> assertThat(row.path("difficulty").asText()).isEqualTo("ADVANCED"));
        assertThat(data(performGet("/api/questions/draw?keyword=never-match-xyz",token)).path("items").isEmpty()).isTrue();
        data(performDelete(BASE+"/"+weak.path("sessionId").asText(),token));
        assertThat(data(performGet("/api/questions/qb-java-001/practices",token)).path("total").asInt()).isZero();
    }
    @Test void rejectsInvalidTrainingAndDrawingInputs() throws Exception {
        String token=user();
        for(int rounds:List.of(1,2,7,12))
            assertThat(startInterview(Map.of("targetRole","练习","questionId","qb-java-001","totalRounds",rounds),token).getResponse().getStatus()).isEqualTo(400);
        for(var extra:List.<Map<String,Object>>of(Map.of("trainingTopic","DATABASE"),Map.of("resumeSummary","新材料"),
                Map.of("retrySessionId","x","retryRound",1),Map.of("questionVersion",0))) {
            var input=new HashMap<String,Object>(Map.of("targetRole","练习","questionId","qb-java-001","totalRounds",3)); input.putAll(extra);
            assertThat(startInterview(input,token).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(startInterview(Map.of("targetRole","练习","questionVersion",1,"totalRounds",3),token).getResponse().getStatus()).isEqualTo(400);
        assertThat(startInterview(Map.of("targetRole","练习","questionId","missing","totalRounds",3),token).getResponse().getStatus()).isEqualTo(404);
        for(String query:List.of("count=0","count=11","roundsPerQuestion=2","roundsPerQuestion=7","topic=INVALID",
                "difficulty=HARD","keyword="+"x".repeat(101)))
            assertThat(performGet("/api/questions/draw?"+query,token).getResponse().getStatus()).isEqualTo(400);
        assertThat(performGet("/api/questions/qb-java-001/practices?size=101",token).getResponse().getStatus()).isEqualTo(400);
    }
    @Test void everyCurrentAndArchivedVersionPassesContentChecks() {
        var versions=jdbc.queryForList("SELECT content_json FROM question_bank_versions",String.class);
        assertThat(versions).hasSize(70);
        for(String value:versions) QuestionContentRules.validate(objectMapper.readValue(value,QuestionSnapshot.class));
        var archived=objectMapper.readTree(jdbc.queryForObject("SELECT content_json FROM question_bank_versions WHERE question_id='qb-java-001' AND version=1",String.class));
        var current=objectMapper.readTree(jdbc.queryForObject("SELECT content_json FROM question_bank_versions WHERE question_id='qb-java-001' AND version=2",String.class));
        assertThat(current.path("referenceAnswer").asText()).startsWith(archived.path("referenceAnswer").asText()).isNotEqualTo(archived.path("referenceAnswer").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank q JOIN question_bank_versions v ON v.question_id=q.id AND v.version=q.version WHERE JSON_UNQUOTE(JSON_EXTRACT(v.content_json,'$.referenceAnswer'))=q.reference_answer",Integer.class)).isEqualTo(64);
    }
    @Test void allSixExpandedTopicsCanFinishWithoutLosingTheirQuestionScope() throws Exception {
        String token=user();
        for(String topic:List.of("java","database","redis","concurrency","performance","reliability")) {
            var original=start("qb-"+topic+"-006",token,3);
            var completed=finish(original,token);
            assertThat(completed.path("state").asText()).isEqualTo("FINISHED");
            assertThat(completed.path("agent").path("training").path("question"))
                    .isEqualTo(original.path("agent").path("training").path("question"));
        }
    }
}
