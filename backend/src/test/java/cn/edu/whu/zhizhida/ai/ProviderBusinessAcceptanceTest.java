package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证业务 JSON 子任务，不能替代持久化 Worker 的端到端质量门禁。 */
class ProviderBusinessAcceptanceTest {
    private static final String PROMPT="你是模拟面试评审。仅依据给出的合成资料和候选人回答，指出一个待核实点并提出一个追问。"
            +"不得补造候选人的经历或指标。只返回 JSON：{\"gap\":\"待核实点\",\"question\":\"一个问题\",\"evidenceQuote\":\"资料中的连续原文\"}。每个字段最多150字。";
    private final JsonMapper json=new JsonMapper();

    List<Map<String,Object>> evaluate(String key, String endpoint, String model) throws Exception {
        return evaluate(key,endpoint,model,rows -> { });
    }
    List<Map<String,Object>> evaluate(String key, String endpoint, String model, java.util.function.Consumer<List<Map<String,Object>>> checkpoint) throws Exception {
        var fixtures=json.readTree(getClass().getResourceAsStream("/provider-business-cases.json"));
        var rows=new ArrayList<Map<String,Object>>();
        int reserved=0, sent=0;
        for(int i=0;i<fixtures.size();i++) {
            var fixture=fixtures.path(i);
            if(sent>=4 || reserved+750>3000) throw new IllegalStateException("Frozen acceptance budget exceeded");
            sent++; reserved+=750;
            var row=new LinkedHashMap<String,Object>(); rows.add(row);
            row.put("caseId",fixture.path("id").asText()); row.put("family",fixture.path("family").asText());
            row.put("status","FAILED_OR_UNKNOWN"); row.put("maxOutputTokens",750);
            row.put("qualityProxyPassed",false); row.put("usage",ModelUsage.unknown(ModelUsage.Status.MISSING));
            checkpoint.accept(rows); // 发送前记录预算预占，中断不能视为零消耗。
            long start=System.nanoTime();
            try {
                var client=new DeepSeekChatClient(key,endpoint,model,"disabled",25,json,cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),BusinessMetrics.disabled());
                var response=client.complete(PROMPT,"合成资料："+fixture.path("material").asText()+"\n候选人回答："+fixture.path("answer").asText(),0,750);
                row.put("usage",response.usage()); row.put("output",response.payload());
                boolean schema=response.payload().isObject() && response.payload().size()==3;
                for(String field:List.of("gap","question","evidenceQuote")) schema &= response.payload().path(field).isString()
                        && !response.payload().path(field).asText().isBlank() && response.payload().path(field).asText().length()<=150;
                String quote=response.payload().path("evidenceQuote").asText();
                boolean grounded=quote.length()>=8 && fixture.path("material").asText().contains(quote);
                String diagnosis=response.payload().path("gap").asText()+response.payload().path("question").asText();
                boolean concepts=true;
                for(var term:fixture.path("gapTerms")) concepts &= diagnosis.contains(term.asText());
                row.put("schemaValid",schema); row.put("quoteSupported",grounded); row.put("gapConceptsPresent",concepts);
                row.put("qualityProxyPassed",schema && grounded && concepts); row.put("status","RETURNED");
            } catch(RuntimeException ex) { row.put("errorCategory",failureCategory(ex)); }
            row.put("elapsedMillis",(System.nanoTime()-start)/1_000_000);
            checkpoint.accept(rows);
        }
        return rows;
    }

    private static String failureCategory(Throwable failure) {
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        String code=null;
        for(Throwable cause=failure;cause!=null && seen.size()<12 && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof cn.edu.whu.zhizhida.common.UpstreamServiceException upstream && upstream.code()!=null && code==null)
                code=upstream.code();
            if(cause instanceof javax.net.ssl.SSLException) return "MODEL_TLS_FAILURE";
            if(cause instanceof java.net.http.HttpTimeoutException) return "MODEL_TIMEOUT";
        }
        return code==null?"PROVIDER_OR_PROTOCOL_FAILURE":code;
    }

    @Test @EnabledIfEnvironmentVariable(named="RUN_PROVIDER_BUSINESS",matches="true")
    void realSyntheticBusinessAcceptance() throws Exception {
        var reportDirectory=Path.of(System.getProperty("provider.report-directory","target"));
        Files.createDirectories(reportDirectory);
        assertThat(System.getenv("BUSINESS_MAX_REQUESTS")).isEqualTo("4");
        assertThat(System.getenv("BUSINESS_MAX_OUTPUT_TOKENS")).isEqualTo("3000");
        String model=System.getenv("ACCEPTANCE_MODEL");
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion",1); report.put("recordedAt",java.time.Instant.now().toString());
        report.put("scope","synthetic-business-json-subtask"); report.put("model",model);
        report.put("tlsTrustStoreType",System.getProperty("javax.net.ssl.trustStoreType","JDK_DEFAULT"));
        report.put("prompt",PROMPT); report.put("temperature",0); report.put("thinking","disabled");
        report.put("maxRequests",4); report.put("reservedOutputTokens",3000);
        report.put("independentReview","PENDING"); report.put("durableWorkerTested",false);
        var rows=evaluate(System.getenv("AI_API_KEY"),System.getenv("ACCEPTANCE_BASE_URL"),model,progress -> {
            report.put("attempts",progress);
            try { Files.writeString(reportDirectory.resolve("provider-business-acceptance.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
            catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        });
        // 供应商失败保留为证据，不静默丢弃或重试；执行脚本另行检查结果完整性。
        assertThat(rows).hasSize(4);
    }

    @Test void offlineFixturePreservesFailuresUnknownUsageAndSendCaps() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            int ordinal=calls.incrementAndGet();
            var input=json.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(input.path("max_tokens").asInt()).isEqualTo(750);
            if(ordinal==1) { exchange.sendResponseHeaders(500,-1); exchange.close(); return; }
            String output="{\"gap\":\"冲突结果\",\"question\":\"冲突后如何返回结果？\",\"evidenceQuote\":\"唯一键冲突应读取原订单结果\"}";
            var body=json.createObjectNode();
            body.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("role","assistant").put("content",output);
            if(ordinal!=2) body.putObject("usage").put("prompt_tokens",20).put("completion_tokens",30).put("total_tokens",50);
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var rows=evaluate("fixture","http://127.0.0.1:"+server.getAddress().getPort(),"fixture");
            assertThat(calls).hasValue(4); assertThat(rows).hasSize(4);
            assertThat(rows.getFirst().get("status")).isEqualTo("FAILED_OR_UNKNOWN");
            assertThat(rows.getFirst().get("errorCategory")).isEqualTo("MODEL_HTTP_500");
            assertThat(((ModelUsage)rows.get(1).get("usage")).status()).isEqualTo(ModelUsage.Status.MISSING);
            assertThat(rows.getFirst().get("qualityProxyPassed")).isEqualTo(false);
            assertThat(rows.get(1).get("qualityProxyPassed")).isEqualTo(true);
            assertThat(rows.getLast().get("qualityProxyPassed")).isEqualTo(false);
            assertThat(failureCategory(cn.edu.whu.zhizhida.common.UpstreamServiceException.coded("MODEL_NETWORK",
                    new javax.net.ssl.SSLHandshakeException("sensitive certificate detail")))).isEqualTo("MODEL_TLS_FAILURE");
        } finally { server.stop(0); }
    }
}
