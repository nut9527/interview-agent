package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 使用真实 PostgreSQL/pgvector 和合成 HTTP embedding，验证存储与协议，不评估模型语义质量。 */
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_ACCEPTANCE",matches="true")
class PgvectorAcceptanceTest {
    @Test void persistsGenerationsAcrossClientsAndCleansPartialDeletedAndOldProfiles() throws Exception {
        var json=new JsonMapper();
        var data=new PGSimpleDataSource();
        // 一次性数据库由启动器管理，此处不得读取应用数据库配置。
        data.setServerNames(new String[]{"127.0.0.1"}); data.setPortNumbers(new int[]{Integer.parseInt(System.getenv("PGVECTOR_TEST_PORT"))});
        data.setDatabaseName("agent_vector_test"); data.setUser("postgres"); data.setPassword(System.getenv("PGVECTOR_TEST_PASSWORD"));
        data.setConnectTimeout(3); data.setSocketTimeout(3);
        String table="acceptance_"+UUID.randomUUID().toString().replace("-","");
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            var body=json.readTree(exchange.getRequestBody().readAllBytes());
            if(calls.incrementAndGet()==2) { exchange.sendResponseHeaders(503,-1); exchange.close(); return; }
            var response=json.createObjectNode().put("model","fixture-3d"); var items=response.putArray("data");
            int count=body.path("input").isArray()?body.path("input").size():1;
            for(int i=0;i<count;i++)items.addObject().put("index",i).put("object","embedding").putArray("embedding").add(1.0).add(0.0).add(0.0);
            response.putObject("usage").put("prompt_tokens",count).put("total_tokens",count);
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var embedding=OpenAiEmbeddingModel.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .apiKey("fixture").modelName("fixture-3d").dimensions(3).maxRetries(0).timeout(Duration.ofSeconds(3)).build();
        var report=new LinkedHashMap<String,Object>();report.put("result","FAILED");
        report.put("realPgvector",true);report.put("realEmbeddingModel",false);report.put("paidCalls",0);
        try(var connection=data.getConnection();var sql=connection.createStatement()) {
            var version=sql.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'");
            assertThat(version.next()).isTrue();report.put("pgvectorVersion",version.getString(1));
            report.put("postgresVersion",connection.getMetaData().getDatabaseProductVersion());
            var store=PgVectorEmbeddingStore.datasourceBuilder().datasource(data).table(table).dimension(3)
                    .createTable(true).skipCreateVectorExtension(true).dropTableFirst(false).build();
            var vectors=new LangChain4jKnowledgeVectors(embedding,store,"fixture-3d:v1");
            var scope=new KnowledgeVectors.Scope("synthetic-doc",1,"synthetic-content-hash");
            var chunks=IntStream.range(0,17).mapToObj(i->new KnowledgeVectors.Fragment("chunk-"+i,"synthetic fragment "+i)).toList();
            var initial=vectors;
            assertThatThrownBy(()->initial.index(scope,chunks)).isInstanceOf(RuntimeException.class);
            assertThat(vectors.search(List.of(scope),"query",30)).isEmpty();
            vectors.index(scope,chunks);vectors.index(scope,chunks);
            var count=sql.executeQuery("SELECT count(*) FROM "+table);count.next();assertThat(count.getInt(1)).isEqualTo(18);
            // 重建存储与适配器，验证发布状态可恢复，不能依赖内存残留。
            var reopened=PgVectorEmbeddingStore.datasourceBuilder().datasource(data).table(table).dimension(3)
                    .createTable(false).skipCreateVectorExtension(true).build();
            vectors=new LangChain4jKnowledgeVectors(embedding,reopened,"fixture-3d:v1");
            assertThat(vectors.search(List.of(scope),"query",30)).hasSize(17);
            var other=new KnowledgeVectors.Scope("other-doc",1,"synthetic-content-hash");
            vectors.index(other,chunks);
            assertThat(vectors.search(List.of(new KnowledgeVectors.Scope("synthetic-doc",2,"synthetic-content-hash")),"query",30)).isEmpty();
            var upgraded=new LangChain4jKnowledgeVectors(embedding,reopened,"fixture-3d:v2");
            assertThat(upgraded.search(List.of(scope),"query",30)).isEmpty();
            upgraded.index(scope,chunks);
            upgraded.remove(scope,vectors.fingerprint());
            assertThat(vectors.search(List.of(scope),"query",30)).isEmpty();
            assertThat(upgraded.search(List.of(scope),"query",30)).hasSize(17);
            upgraded.remove(scope,upgraded.fingerprint());upgraded.remove(scope,upgraded.fingerprint());
            assertThat(upgraded.search(List.of(scope),"query",30)).isEmpty();
            assertThat(vectors.search(List.of(other),"query",30)).hasSize(17);
            report.put("checks",List.of("partial-generation-hidden","idempotent-upsert","client-restart-persistence",
                    "document-version-profile-isolation","old-profile-cleanup","delete-idempotency","unrelated-document-preserved"));
            report.put("result","PASSED");
        } finally {
            server.stop(0);
            // 仅清理测试生成标识符对应的表，不能删除已有应用表。
            try(var c=data.getConnection();var s=c.createStatement()) { s.execute("DROP TABLE IF EXISTS "+table); }
            report.put("embeddingHttpRequests",calls.get());report.put("recordedAt",java.time.Instant.now().toString());
            Files.writeString(Path.of("target/pgvector-acceptance.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
