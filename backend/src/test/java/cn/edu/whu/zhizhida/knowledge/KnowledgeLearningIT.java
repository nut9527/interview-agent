package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeLearningIT extends IntegrationTestBase {
    static final String BASE="/api/knowledge";
    @Autowired JdbcTemplate jdbc;
    String user() throws Exception { return registerUniqueUserToken("password123","知识学习"); }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    JsonNode document(String token) throws Exception { return data(performPost(BASE+"/documents",Map.of("title","缓存笔记","content","# 缓存策略\n\n缓存 TTL 90 秒，使用随机过期时间防止缓存雪崩。\n\n空值缓存用于防止缓存穿透，存活 5 秒。"),token)); }
    Map<String,Object> generate(String doc,int version,List<String> chunks) { return Map.of("sources",List.of(Map.of("documentId",doc,"version",version,"chunkIds",chunks)),"count",3); }
    Map<String,Object> confirmation() { return Map.of("stem","缓存 TTL 为何使用随机过期？","point","随机过期","referencePoints",List.of("避免同时过期"),"confirmed",true); }
    @Test void draftsRequireIndividualConfirmationRetainSourcesAndSurviveSourceDeletion() throws Exception {
        String token=user(),other=user(); var doc=document(token); String id=doc.path("id").asText();
        String chunk=doc.path("chunks").get(1).path("id").asText();
        var generated=data(performPost(BASE+"/self-tests/generate",generate(id,1,List.of(chunk)),token));
        assertThat(generated).hasSize(1); String question=generated.get(0).path("id").asText(); String path=BASE+"/self-tests/"+question;
        assertThat(data(performGet(BASE+"/self-tests",token)).path("total").asInt()).isZero();
        assertThat(data(performGet(BASE+"/self-tests?confirmed=false",token)).path("total").asInt()).isEqualTo(1);
        assertThat(performPost(path+"/confirm",confirmation(),other).getResponse().getStatus()).isEqualTo(404);
        var unconfirmed=new HashMap<>(confirmation());unconfirmed.put("confirmed",false);
        assertThat(performPost(path+"/confirm",unconfirmed,token).getResponse().getStatus()).isEqualTo(400);
        data(performPut(BASE+"/documents/"+id+"/content",Map.of("content","TTL 180 秒。","expectedVersion",1),token));
        var saved=data(performPost(path+"/confirm",confirmation(),token));
        assertThat(saved.path("confirmed").asBoolean()).isTrue();
        assertThat(saved.path("question").path("citations")).isEqualTo(generated.get(0).path("question").path("citations"));
        assertThat(data(performPost(path+"/confirm",confirmation(),token))).isEqualTo(saved);
        assertThat(saved.path("sources").get(0).path("version").asInt()).isEqualTo(1);
        data(performDelete(BASE+"/documents/"+id,token));
        var reopened=data(performGet(BASE+"/self-tests",token)).path("items").get(0);
        assertThat(reopened.path("sources").get(0).path("available").asBoolean()).isFalse();
        assertThat(reopened.path("question")).isEqualTo(saved.path("question"));
        assertThat(data(performGet(BASE+"/self-tests",other)).path("items")).isEmpty();
        assertThat(performDelete(path,other).getResponse().getStatus()).isEqualTo(404);
        data(performDelete(path,token));
        assertThat(performDelete(path,token).getResponse().getStatus()).isEqualTo(404);
    }
    @Test void generationRejectsForeignVersionsChunksDuplicatesAndLimits() throws Exception {
        String owner=user(),other=user(); var doc=document(owner);String id=doc.path("id").asText();
        assertThat(performPost(BASE+"/self-tests/generate",generate(id,1,List.of()),other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(BASE+"/self-tests/generate",generate(id,99,List.of()),owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(BASE+"/self-tests/generate",generate(id,1,List.of("unknown")),owner).getResponse().getStatus()).isEqualTo(400);
        var selection=Map.of("documentId",id,"version",1,"chunkIds",List.of());
        assertThat(performPost(BASE+"/self-tests/generate",Map.of("sources",List.of(selection,selection),"count",2),owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(performPost(BASE+"/self-tests/generate",Map.of("sources",List.of(selection),"count",0),owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(data(performPost(BASE+"/self-tests/generate",generate(id,1,List.of()),owner))).isNotEmpty();
    }
    @Test void batchOrganizationIsAtomicPrivateAndDoesNotChangeVersions() throws Exception {
        String owner=user(),other=user(); String id=document(owner).path("id").asText(),foreign=document(other).path("id").asText();
        var valid=Map.of("documentIds",List.of(id),"category","数据库","tags",List.of("缓存","缓存","Redis"));
        data(performPut(BASE+"/organization",valid,owner));
        var meta=data(performGet(BASE+"/organization",owner)); assertThat(meta).hasSize(1);assertThat(meta.get(0).path("tags")).hasSize(2);
        assertThat(data(performGet(BASE+"/organization",other))).isEmpty();
        assertThat(performPut(BASE+"/organization",Map.of("documentIds",List.of(id,foreign),"category","Java","tags",List.of()),owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet(BASE+"/organization",owner))).isEqualTo(meta);
        assertThat(data(performGet(BASE+"/documents/"+id,owner)).path("version").asInt()).isEqualTo(1);
        data(performDelete(BASE+"/documents/"+id,owner)); assertThat(data(performGet(BASE+"/organization",owner))).isEmpty();
    }
    @Test void feedbackUpdatesAndListsFailureSamplesWithOwnershipAndCleanup() throws Exception {
        String owner=user(),other=user(),id=document(owner).path("id").asText(),missing=document(owner).path("id").asText();
        String answer=data(performPost(BASE+"/answers",Map.of("question","缓存 TTL","documentIds",List.of(id)),owner)).path("historyId").asText();
        String path=BASE+"/answers/"+answer+"/feedback";
        assertThat(data(performGet(path,owner)).path("reasons")).isEmpty();
        var request=Map.of("reasons",List.of("UNRESOLVED","UNSUPPORTED_CITATION","MISSING_MATERIAL"),"comment","引用没有解释适用边界","missingDocumentIds",List.of(missing));
        var saved=data(performPut(path,request,owner));assertThat(saved.path("missingSources").get(0).path("version").asInt()).isEqualTo(1);
        data(performPut(path,request,owner)); assertThat(data(performGet(BASE+"/feedback",owner)).path("total").asInt()).isEqualTo(1);
        assertThat(performGet(path,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPut(path,request,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet(BASE+"/feedback",other)).path("items")).isEmpty();
        assertThat(performPut(path,Map.of("reasons",List.of("INVALID"),"comment","","missingDocumentIds",List.of()),owner).getResponse().getStatus()).isEqualTo(400);
        data(performDelete(BASE+"/documents/"+missing,owner));
        assertThat(data(performGet(path,owner)).path("missingSources").get(0).path("available").asBoolean()).isFalse();
        data(performDelete(BASE+"/answers/"+answer,owner)); assertThat(data(performGet(BASE+"/feedback",owner)).path("items")).isEmpty();
    }
    @Test void followupsPinOldVersionsEnforceScopeAndStoreParent() throws Exception {
        String owner=user(),other=user(),id=document(owner).path("id").asText(),different=document(owner).path("id").asText();
        var first=data(performPost(BASE+"/answers",Map.of("question","缓存 TTL","documentIds",List.of(id)),owner));String parent=first.path("historyId").asText();
        data(performPut(BASE+"/documents/"+id+"/content",Map.of("content","TTL 180 秒。","expectedVersion",1),owner));
        var request=Map.of("question","为什么这样？它有什么边界？","documentIds",List.of(id),"parentAnswerId",parent);
        var second=data(performPost(BASE+"/answers",request,owner));
        assertThat(second.path("claims").get(0).path("citations").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(data(performGet(BASE+"/answers/"+second.path("historyId").asText(),owner)).path("parentAnswerId").asText()).isEqualTo(parent);
        assertThat(performPost(BASE+"/answers",request,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(BASE+"/answers",Map.of("question","为什么","documentIds",List.of(different),"parentAnswerId",parent),owner).getResponse().getStatus()).isEqualTo(400);
        data(performDelete(BASE+"/answers/"+parent,owner));
        assertThat(data(performGet(BASE+"/answers/"+second.path("historyId").asText(),owner)).path("parentAnswerId").isNull()).isTrue();
    }
    @Test void accountClosureCleansAllLearningData() throws Exception {
        String owner=user(),id=document(owner).path("id").asText();
        var questions=data(performPost(BASE+"/self-tests/generate",generate(id,1,List.of()),owner));String question=questions.get(0).path("id").asText();
        data(performPut(BASE+"/organization",Map.of("documentIds",List.of(id),"category","Java","tags",List.of()),owner));
        data(performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),owner));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_self_tests WHERE id=?",Integer.class,question)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_organization WHERE document_id=?",Integer.class,id)).isZero();
    }
}
