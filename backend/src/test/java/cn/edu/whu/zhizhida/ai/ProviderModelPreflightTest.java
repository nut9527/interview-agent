package cn.edu.whu.zhizhida.ai;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 仅探测模型信息，不执行推理；证据和断言不写入 Prompt 或密钥。 */
class ProviderModelPreflightTest {
    static boolean listed(tools.jackson.databind.JsonNode body,String model) {
        if(!body.path("data").isArray()) return false;
        for(var item:body.path("data")) if(item.path("id").isString() && model.equals(item.path("id").asText())) return true;
        return false;
    }
    @Test void rejectsMissingMalformedAndUnavailableModels() {
        var json=new JsonMapper();
        assertThat(listed(json.readTree("{\"data\":[{\"id\":\"available\"}]}"),"available")).isTrue();
        assertThat(listed(json.readTree("{\"data\":[{\"id\":\"available\"}]}"),"retired")).isFalse();
        assertThat(listed(json.readTree("{\"data\":{\"id\":\"available\"}}"),"available")).isFalse();
        assertThat(listed(json.readTree("{}"),"available")).isFalse();
    }
    @Test @EnabledIfEnvironmentVariable(named="RUN_PROVIDER_PREFLIGHT",matches="true")
    void configuredModelMustBeListedBeforeInference() throws Exception {
        var json=new JsonMapper();
        String endpoint=System.getenv("ACCEPTANCE_BASE_URL"),model=System.getenv("ACCEPTANCE_MODEL"),key=System.getenv("AI_API_KEY");
        var uri=URI.create(endpoint);
        if(!"https".equals(uri.getScheme()) || !"api.deepseek.com".equals(uri.getHost()) || uri.getPort()!=-1
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || !List.of("","/","/v1","/v1/").contains(uri.getPath()) || key==null || key.isBlank())
            throw new IllegalArgumentException("Official provider and configured key required");
        var report=new LinkedHashMap<String,Object>();
        report.put("recordedAt",java.time.Instant.now().toString());report.put("modelRequests",0);
        report.put("scope","read-only-model-discovery-not-inference");report.put("configuredModel",model);
        report.put("tlsTrustStoreType",System.getProperty("javax.net.ssl.trustStoreType","JDK_DEFAULT"));
        report.put("configuredModelListed",false);report.put("result","FAILED");
        try {
            var request=HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$","")+"/models"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+key).GET().build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                    .build().send(request,HttpResponse.BodyHandlers.ofString());
            report.put("httpStatus",response.statusCode());
            if(response.statusCode()==200) {
                var body=json.readTree(response.body());
                var names=new ArrayList<String>();
                for(var item:body.path("data")) {
                    String name=item.path("id").asText();if(name.matches("[A-Za-z0-9._-]{1,100}"))names.add(name);
                }
                report.put("availableModels",names);
                report.put("configuredModelListed",listed(body,model));
                if(listed(body,model))report.put("result","PASSED");
            }
        } catch(Exception ex) { report.put("errorCategory",ex instanceof javax.net.ssl.SSLException?"TLS_FAILURE":"DISCOVERY_FAILURE"); }
        finally { Files.writeString(Path.of("target/provider-model-preflight.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
        assertThat(report.get("result")).as("Configured model must be available; no inference sent").isEqualTo("PASSED");
    }
}
