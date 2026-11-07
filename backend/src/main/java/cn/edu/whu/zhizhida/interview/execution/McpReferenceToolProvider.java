package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.ai.api.ReferenceToolClient;
import cn.edu.whu.zhizhida.common.execution.ToolRegistry;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** 外部文本仅为不可信参考资料，不作为用户事实或知识引用。 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.mcp.reference.enabled", havingValue = "true")
public final class McpReferenceToolProvider implements InterviewToolProvider {
    private final ReferenceToolClient client;
    private final ObjectMapper json;
    public McpReferenceToolProvider(ReferenceToolClient client, ObjectMapper json) { this.client = client; this.json = json; }
    public List<ToolRegistry.Registration<Context, InterviewEvidenceTools.Result>> tools() {
        var args = new EvidenceToolArguments("query", "topK", 300, 3);
        return List.of(new ToolRegistry.Registration<>(args.definition(json, "search_reference",
                "用通用技术关键词检索本地文档目录，获取阅读提示和官方文档链接。只传技术关键词；结果仅供技术参考。"),
                client.fingerprint(), Set.of(), new ToolRegistry.Limits(5000, 12000),
                (context, input) -> args.validate(input), (context, input) -> {
                    var content = client.search(input.path("query").asText(), input.path("topK").asInt());
                    return new InterviewEvidenceTools.Result(json.createObjectNode()
                            .put("status", "[]".equals(content) ? "NO_MATCH" : "FOUND")
                            .put("trust", "UNTRUSTED_REFERENCE").put("content", content), List.of());
                }));
    }
}
