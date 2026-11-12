package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 本地演示走同一工具协议；不调用外部模型，也不宣称完成语义诊断。 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockToolChatClient implements ToolCallingChatClient {
    private final ObjectMapper json;
    public MockToolChatClient(ObjectMapper json) { this.json = json; }
    public Config configuration() { return new Config("mock", "disabled", "mock-tools-v1"); }
    public Response complete(Request request) {
        if (!request.messages().getFirst().path("content").asText().startsWith("LEARNING_GOAL_AGENT_V1"))
            throw new IllegalArgumentException("Unsupported mock tool task");
        var input = json.readTree(request.messages().get(1).path("content").asText());
        var message = json.createObjectNode().put("role", "assistant");
        long receipts = request.messages().stream().filter(m -> m.path("role").asText().equals("tool")).count();
        if (receipts >= 2) return new Response(message.put("content", "{\"action\":\"COMPLETE\"}"), "stop", ModelUsage.unknown(ModelUsage.Status.MISSING));
        String name;
        var args = json.createObjectNode();
        if (receipts == 0) {
            name = "search_training_questions";
            args.put("query", input.path("goal").path("title").asText());
        } else {
            name = "propose_training_plan";
            var search = json.readTree(request.messages().stream().filter(m -> m.path("role").asText().equals("tool"))
                    .findFirst().orElseThrow().path("content").asText());
            var items = args.putArray("items");
            var completed = new java.util.HashSet<String>();
            for (var result : search.path("results")) if (result.path("completed").asBoolean()) completed.add(result.path("questionId").asText());
            int available = input.path("goal").path("maxTraining").asInt() - input.path("goal").path("reservedTraining").asInt();
            var questions = java.util.stream.StreamSupport.stream(search.path("questions").spliterator(), false)
                    .sorted(java.util.Comparator.comparing(q -> completed.contains(q.path("id").asText())))
                    .limit(Math.max(0, available)).toList();
            for (var question : questions)
                items.addObject().put("itemKey", "item-" + (items.size() + 1))
                        .put("questionId", question.path("id").asText()).put("questionVersion", question.path("version").asInt())
                        .put("title", question.path("title").asText())
                        .put("acceptance", "演示练习：说明个人行动、判断依据和失败边界，完成后核对原回答。");
            args.put("reason", "规则演示按已选范围安排诊断练习，未进行模型语义规划。");
            args.putArray("focus");
        }
        message.putArray("tool_calls").addObject().put("id", "mock-goal-" + receipts).put("type", "function")
                .putObject("function").put("name", name).put("arguments", args.toString());
        return new Response(message, "tool_calls", ModelUsage.unknown(ModelUsage.Status.MISSING));
    }
}
