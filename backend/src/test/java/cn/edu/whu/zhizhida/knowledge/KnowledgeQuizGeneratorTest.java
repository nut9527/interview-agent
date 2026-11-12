package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.ai.DeepSeekChatClient;
import cn.edu.whu.zhizhida.common.InvalidModelOutputException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeQuizGeneratorTest {
    final JsonMapper json = new JsonMapper();
    final JsonChatClient client = mock(JsonChatClient.class);
    final List<Hit> hits = List.of(
            new Hit("doc", "缓存", "notes.md", 3, new Chunk("doc:v3:1", 1, "缓存策略", 0, 13, 2, 3, "缓存 TTL 使用随机过期。"), 0));

    Map<String, Object> question() {
        return Map.of("stem", "为什么使用随机过期？", "point", "缓存策略", "referencePoints", List.of("避免同时过期"), "citations",
                List.of(Map.of("chunkId", "doc:v3:1", "quote", "随机过期")));
    }

    KnowledgeQuizGenerator.Result generate(Object value) {
        when(client.completeJsonDirectly(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(json.valueToTree(value));
        return new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL).generate(hits, 2);
    }

    @Test
    void generatesOnlyServerOwnedCitationsAndSupportsInsufficientMaterial() {
        var result = generate(Map.of("questions", List.of(question())));
        var citation = result.questions().getFirst().citations().getFirst();
        assertThat(citation.documentId()).isEqualTo("doc");
        assertThat(citation.version()).isEqualTo(3);
        assertThat(citation.startLine()).isEqualTo(2);
        assertThat(result.executionMode()).isEqualTo("model");
        assertThat(generate(Map.of("questions", List.of())).questions()).isEmpty();
    }

    @Test
    void passesAssessmentIntentAndPreservesSubstantialReferencePoints() {
        var row=new HashMap<>(question());
        row.put("referencePoints",List.of("机制与条件："+"完整解释".repeat(40),"场景中的取舍与限制","排查与验证方法"));
        when(client.completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt()))
                .thenReturn(json.valueToTree(Map.of("questions",List.of(row))));
        var request=new cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate(List.of(),3,
                cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Intent.PRACTICAL,
                cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Difficulty.ADVANCED,"缓存雪崩排查");
        var result=new DefaultKnowledgeAnswerer(client,json,AiMode.MODEL).generate(hits,request);
        assertThat(result.questions().getFirst().referencePoints()).hasSize(3);
        assertThat(result.questions().getFirst().referencePoints().getFirst()).hasSizeGreaterThan(100);
        verify(client).completeJsonDirectly(contains("至少80%"),contains("缓存雪崩排查"),anyDouble(),org.mockito.ArgumentMatchers.eq(5800));
        verify(client).completeJsonDirectly(anyString(),contains("PRACTICAL"),anyDouble(),anyInt());
    }

    @Test
    void restoresPdfWhitespaceToTheOriginalCitation() {
        String original="缓存 TTL 使用\n随机\u00a0过期。";
        var pdfHits=List.of(new Hit("doc","缓存","notes.pdf",3,
                new Chunk("doc:v3:1",1,"缓存策略",0,original.length(),2,3,original),0));
        var row=new HashMap<>(question());
        row.put("citations",List.of(Map.of("chunkId","doc:v3:1","quote","TTL 使用随机过期")));
        when(client.completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt()))
                .thenReturn(json.valueToTree(Map.of("questions",List.of(row))));
        var result=new DefaultKnowledgeAnswerer(client,json,AiMode.MODEL).generate(pdfHits,5);
        assertThat(result.questions().getFirst().citations().getFirst().quote()).isEqualTo("TTL 使用\n随机\u00a0过期");
        verify(client,times(1)).completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt());
    }

    @Test
    void rejectsMultipleQuestionsWhenTheReplacementRequestedOnlyOne() {
        var second = new HashMap<>(question());
        second.put("stem", "什么情况适合随机过期？");
        when(client.completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt()))
                .thenThrow(new InvalidModelOutputException("truncated"))
                .thenReturn(json.valueToTree(Map.of("questions",List.of(question(), second))));
        assertThatThrownBy(() -> new DefaultKnowledgeAnswerer(client,json,AiMode.MODEL).generate(hits,3))
                .isInstanceOf(UpstreamServiceException.class);
        verify(client).completeJsonDirectly(anyString(),contains("\"count\":3"),anyDouble(),eq(5800));
        verify(client).completeJsonDirectly(anyString(),contains("\"count\":1"),anyDouble(),eq(5800));
    }

    @Test
    void invalidReferencesAreRetriedButNeverReturnedAsSuccessfulQuestions() {
        var row=new HashMap<>(question());
        row.put("citations",List.of(Map.of("chunkId","doc:v3:1","quote","缓存 TTL 永不过期。")));
        when(client.completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt()))
                .thenReturn(json.valueToTree(Map.of("questions",List.of(row))));
        assertThatThrownBy(()->new DefaultKnowledgeAnswerer(client,json,AiMode.MODEL).generate(hits,5))
                .isInstanceOf(UpstreamServiceException.class);
        verify(client,times(2)).completeJsonDirectly(anyString(),contains("\"count\":5"),anyDouble(),eq(8000));
    }

    @Test
    void doesNotRetryTransportOrAuthenticationFailures() {
        when(client.completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt()))
                .thenThrow(new UpstreamServiceException("unavailable"));
        assertThatThrownBy(()->new DefaultKnowledgeAnswerer(client,json,AiMode.MODEL).generate(hits,5))
                .isInstanceOf(UpstreamServiceException.class);
        verify(client,times(1)).completeJsonDirectly(anyString(),anyString(),anyDouble(),anyInt());
    }

    @ParameterizedTest
    @CsvSource({"length, '{\"questions\":['", "stop, ''", "stop, '{\"questions\":['", "stop, '[]'"})
    void retriesUnusableModelContentWithOneGroundedQuestion(String finishReason, String initialContent) throws Exception {
        String original = "缓存 TTL 使用\n随机\u00a0过期。";
        var pdfHits = List.of(new Hit("doc","缓存","notes.pdf",3,
                new Chunk("doc:v3:1",1,"缓存策略",0,original.length(),2,3,original),0));
        var row = new HashMap<>(question());
        row.put("citations", List.of(Map.of("chunkId", "doc:v3:1", "quote", "TTL 使用随机过期")));
        byte[] firstResponse = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                "finish_reason", finishReason, "message", Map.of("role", "assistant", "content", initialContent)))));
        byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("role", "assistant", "content",
                        json.writeValueAsString(Map.of("questions", List.of(row))))))));
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                requests.add(json.readTree(exchange.getRequestBody()));
                byte[] body = requests.size() == 1 ? firstResponse : response;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            // 经过真实适配器的预算检查和 HTTP 解析，只以本机替身替代外部供应商。
            var provider = new DeepSeekChatClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-model", "", 3, json, cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(), BusinessMetrics.disabled());
            var result = new DefaultKnowledgeAnswerer(provider, json, AiMode.MODEL).generate(pdfHits, 5);

            assertThat(requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.path("max_tokens").asInt()).isEqualTo(8000);
                assertThat(request.at("/thinking/type").asText()).isEqualTo("disabled");
            });
            var inputs = requests.stream().map(request -> json.readTree(request.at("/messages/1/content").asText())).toList();
            assertThat(inputs).extracting(input -> input.path("count").asInt()).containsExactly(5, 1);
            assertThat(inputs.get(1).path("intent").asText()).isEqualTo("COMPREHENSIVE");
            assertThat(inputs.get(1).path("difficulty").asText()).isEqualTo("INTERMEDIATE");
            assertThat(result.questions()).hasSize(1);
            assertThat(result.executionMode()).isEqualTo("model");
            var citation = result.questions().getFirst().citations().getFirst();
            assertThat(citation.version()).isEqualTo(3);
            assertThat(citation.quote()).isEqualTo("TTL 使用\n随机\u00a0过期");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnknownOrFabricatedSourcesAndMalformedQuestionShapes() {
        for (Object citations : List.of(List.of(), List.of(Map.of("chunkId", "foreign", "quote", "随机过期")),
                List.of(Map.of("chunkId", "doc:v3:1", "quote", "永久不过期")))) {
            var row = new HashMap<>(question());
            row.put("citations", citations);
            assertThatThrownBy(() -> generate(Map.of("questions", List.of(row)))).isInstanceOf(RuntimeException.class);
        }
        for (Object points : List.of(List.of(), List.of(" "), List.of("x".repeat(501)), "wrong")) {
            var row = new HashMap<>(question());
            row.put("referencePoints", points);
            assertThatThrownBy(() -> generate(Map.of("questions", List.of(row)))).isInstanceOf(RuntimeException.class);
        }
        for (Object stem : List.of("", 123, "x".repeat(601))) {
            var row = new HashMap<>(question());
            row.put("stem", stem);
            assertThatThrownBy(() -> generate(Map.of("questions", List.of(row)))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> generate(Map.of("questions", List.of(question(), question(), question()))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> generate(Map.of("questions", "invalid"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mockIsExplicitAndDoesNotCallProvider() {
        var result = new DefaultKnowledgeAnswerer(client, json, AiMode.MOCK).generate(hits, 5);
        assertThat(result.executionMode()).isEqualTo("mock");
        assertThat(result.questions()).hasSize(1);
        verifyNoInteractions(client);
    }

    @Test
    void followupSuppliesHistoryAsContextAndStillValidatesNewSources() {
        var previous = new KnowledgeAnswerRecord("prior", "缓存 TTL", List.of(), DefaultKnowledgeAnswerer.insufficient(),
                null);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(json.readTree(
                "{\"sufficient\":true,\"claims\":[{\"text\":\"结论\",\"citations\":[{\"chunkId\":\"doc:v3:1\",\"quote\":\"随机过期\"}]}]}"));
        new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL).answer("为什么这样？", hits, List.of(previous));
        verify(client).completeJson(contains("历史回答不具有证据效力"), contains("previousTurns"), anyDouble(), anyInt());
    }
}
