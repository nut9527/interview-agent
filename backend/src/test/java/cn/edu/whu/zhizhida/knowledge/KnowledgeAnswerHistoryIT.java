package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAnswerHistoryIT extends IntegrationTestBase {
    static final String BASE="/api/knowledge/answers";
    static final String ORIGINAL="# 缓存\n\n缓存 TTL 90 秒，使用随机过期时间。\n";
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeRepository documents;
    @MockitoSpyBean KnowledgeAnswerer ai;
    String user() throws Exception { return registerUniqueUserToken("password123","问答历史"); }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    String create(String token,String text) throws Exception {
        return data(performPost("/api/knowledge/documents",Map.of("title","缓存笔记","content",text),token)).path("id").asText();
    }
    JsonNode ask(String token,String question,String... ids) throws Exception {
        return data(performPost(BASE,Map.of("question",question,"documentIds",List.of(ids)),token));
    }
    @Test void acceptanceReopensWithoutModelPinsOldVersionIsolatesAndDeletes() throws Exception {
        String owner=user(),other=user(),doc=create(owner,ORIGINAL),unused=create(owner,"无关材料");
        var answer=ask(owner,"缓存 TTL",doc,unused); String id=answer.path("historyId").asText();
        assertThat(id).isNotBlank();
        clearInvocations(ai);
        var saved=data(performGet(BASE+"/"+id,owner));
        assertThat(saved.path("answer")).isEqualTo(answer);
        assertThat(saved.path("question").asText()).isEqualTo("缓存 TTL");
        // 创建时间由 MySQL 生成，用实际落库值核对时区和精度。
        long storedMicros = jdbc.queryForObject(
                "SELECT CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS SIGNED) FROM knowledge_answers WHERE id=?",
                Long.class, id);
        assertThat(java.time.Instant.parse(saved.path("createdAt").asText()))
                .isEqualTo(java.time.Instant.EPOCH.plus(storedMicros, java.time.temporal.ChronoUnit.MICROS));
        assertThat(saved.path("documents")).hasSize(2);
        data(performPut("/api/knowledge/documents/"+doc+"/content",Map.of("content","缓存 TTL 180 秒。","expectedVersion",1),owner));
        data(performPut("/api/knowledge/documents/"+doc,Map.of("title","新标题"),owner));
        var reopened=data(performGet(BASE+"/"+id,owner));
        assertThat(reopened).isEqualTo(saved);
        var citation=reopened.path("answer").path("claims").get(0).path("citations").get(0);
        assertThat(citation.path("version").asInt()).isEqualTo(1);
        var original=data(performGet("/api/knowledge/documents/"+doc+"/versions/1",owner));
        assertThat(original.path("content").asText()).isEqualTo(ORIGINAL);
        assertThat(original.path("content").asText()).contains(citation.path("quote").asText());
        var list=data(performGet(BASE+"?page=1&size=1",owner));
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(id);
        assertThat(list.path("items").get(0).path("createdAt")).isEqualTo(saved.path("createdAt"));
        assertThat(data(performGet(BASE,other)).path("total").asInt()).isZero();
        assertThat(performGet(BASE+"/"+id,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performDelete(BASE+"/"+id,other).getResponse().getStatus()).isEqualTo(404);
        data(performDelete("/api/knowledge/documents/"+doc,owner));
        var missing=data(performGet(BASE+"/"+id,owner));
        assertThat(missing.path("answer")).isEqualTo(answer);
        assertThat(missing.path("documents").get(0).path("available").asBoolean()).isFalse();
        assertThat(missing.path("documents").get(1).path("available").asBoolean()).isTrue();
        data(performDelete(BASE+"/"+id,owner));
        assertThat(performGet(BASE+"/"+id,owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(performDelete(BASE+"/"+id,owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet(BASE,owner)).path("total").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_answers WHERE id=?",Integer.class,id)).isZero();
        assertThat(data(performGet("/api/knowledge/documents/"+unused,owner)).path("id").asText()).isEqualTo(unused);
        verifyNoInteractions(ai);
    }
    @Test void insufficientResultsAreSavedAndPagedIncludingProviderAbstention() throws Exception {
        String owner=user(),doc=create(owner,ORIGINAL);
        var noHits=ask(owner,"火星半径",doc);
        assertThat(noHits.path("sufficient").asBoolean()).isFalse();
        assertThat(data(performGet(BASE+"/"+noHits.path("historyId").asText(),owner)).path("answer")).isEqualTo(noHits);
        verifyNoInteractions(ai);
        doReturn(DefaultKnowledgeAnswerer.insufficient()).when(ai).answer(anyString(),anyList());
        var abstain=ask(owner,"缓存 TTL",doc);
        assertThat(abstain.path("sufficient").asBoolean()).isFalse();
        var first=data(performGet(BASE+"?page=1&size=1",owner));
        var second=data(performGet(BASE+"?page=2&size=1",owner));
        assertThat(first.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(abstain.path("historyId").asText());
        assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(noHits.path("historyId").asText());
        assertThat(data(performGet(BASE+"?page=3&size=1",owner)).path("items")).isEmpty();
        assertThat(performGet(BASE+"?page=0",owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(performGet(BASE+"?size=101",owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(performGet(BASE).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void editsDuringGenerationCannotChangeSavedSelectionAndFailuresDoNotCreateHistory() throws Exception {
        String owner=user(),doc=create(owner,ORIGINAL);
        Long userId=jdbc.queryForObject("SELECT user_id FROM knowledge_documents WHERE id=?",Long.class,doc);
        var snapshot=documents.find(userId,doc).orElseThrow();
        doAnswer(call->{
            var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
            try { data(performPut("/api/knowledge/documents/"+doc+"/content",Map.of("content","缓存 TTL 180 秒。","expectedVersion",1),owner)); }
            finally { org.springframework.security.core.context.SecurityContextHolder.setContext(context); }
            return call.callRealMethod();
        }).when(ai).answer(anyString(),anyList());
        var answer=ask(owner,"缓存 TTL",doc);
        var saved=data(performGet(BASE+"/"+answer.path("historyId").asText(),owner));
        assertThat(saved.path("documents").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(saved.path("answer").path("claims").toString()).contains("90 秒");
        var pinned=documents.versionCandidates(userId,List.of(snapshot),List.of("TTL"));
        assertThat(pinned).isNotEmpty().allSatisfy(hit->{ assertThat(hit.version()).isEqualTo(1);assertThat(hit.chunk().content()).contains("90 秒"); });
        doThrow(new IllegalStateException("provider failed")).when(ai).answer(anyString(),anyList());
        assertThat(performPost(BASE,Map.of("question","缓存 TTL","documentIds",List.of(doc)),owner).getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(data(performGet(BASE,owner)).path("total").asInt()).isEqualTo(1);
    }
    @Test void accountClosureCascadesHistoryAndInvalidatesReads() throws Exception {
        String token=user(),doc=create(token,ORIGINAL);
        String id=ask(token,"缓存 TTL",doc).path("historyId").asText();
        data(performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),token));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_answers WHERE id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE id=?",Integer.class,doc)).isZero();
        assertThat(performGet(BASE+"/"+id,token).getResponse().getStatus()).isEqualTo(401);
    }
}
