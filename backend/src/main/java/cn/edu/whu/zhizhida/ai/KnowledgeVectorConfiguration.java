package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

/** 仅供显式启用的本地开发使用；不创建扩展、表或用户材料索引。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.vector.enabled", havingValue = "true")
public class KnowledgeVectorConfiguration {
    @Bean KnowledgeVectors knowledgeVectors(@Value("${agent.vector.embedding-url}") String url,
            @Value("${agent.vector.embedding-model}") String model, @Value("${agent.vector.dimension}") int dimension,
            @Value("${agent.vector.embedding-version}") String version, @Value("${agent.vector.pg-host:127.0.0.1}") String host,
            @Value("${agent.vector.pg-port:5432}") int port, @Value("${agent.vector.pg-database}") String database,
            @Value("${agent.vector.pg-user}") String user, @Value("${agent.vector.pg-password}") String password) {
        var uri = java.net.URI.create(url);
        if (!"http".equals(uri.getScheme()) || !java.util.List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || dimension < 1 || dimension > 4096)
            throw new IllegalArgumentException("Vector development slice requires a local embedding endpoint and valid dimensions");
        var embedding = OpenAiEmbeddingModel.builder().baseUrl(url).modelName(model).apiKey("local")
                .dimensions(dimension).maxRetries(0).timeout(Duration.ofSeconds(3)).build();
        var data = new org.postgresql.ds.PGSimpleDataSource();
        data.setServerNames(new String[]{host}); data.setPortNumbers(new int[]{port}); data.setDatabaseName(database);
        data.setUser(user); data.setPassword(password); data.setConnectTimeout(3); data.setSocketTimeout(3);
        var store = PgVectorEmbeddingStore.datasourceBuilder().datasource(data)
                .table("knowledge_vectors").dimension(dimension).createTable(false).skipCreateVectorExtension(true)
                .dropTableFirst(false).build();
        return new LangChain4jKnowledgeVectors(embedding, store, model + ":" + dimension + ":" + version);
    }
}
