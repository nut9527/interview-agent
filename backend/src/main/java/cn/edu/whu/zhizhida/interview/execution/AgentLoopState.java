package cn.edu.whu.zhizhida.interview.execution;

import java.util.Locale;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 按确定性规则记录证据的可用性，不采信模型自行宣称的语义进展。 */
public final class AgentLoopState {
    private AgentLoopState() { }

    public record Observation(String decision, String outcome, int newEvidence, String before, String after,
            String actionKey, String sourceKeys, String stopReason) { }

    public static String policy(JsonNode config) { return config.path("loopPolicy").asText("B0"); }

    public static Observation observe(ObjectMapper json, ObjectNode cp, String name, JsonNode args, JsonNode result) {
        return observe(json, cp, name, args, result, false);
    }

    public static Observation observe(ObjectMapper json, ObjectNode cp, String name, JsonNode args, JsonNode result,
            boolean memoryTool) {
        var state = cp.withObject("loop");
        var keys = strings(state.path("evidence"));
        var actions = strings(state.path("actions"));
        String before = fingerprint(keys, actions);
        String action = name + ":" + cn.edu.whu.zhizhida.common.JsonFingerprint.canonical(args);
        if (name.equals("search_knowledge")) action = name + ":" + args.path("query").asText().strip()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String actionKey = InterviewRunStore.sha(action);
        boolean repeated = !actions.add(actionKey);
        var found = new TreeSet<String>();
        // 此处字段均由服务端工具基于已固定、已授权的快照生成。
        if (result.path("status").asText().equals("FOUND")) {
            if (result.has("content")) found.add(InterviewRunStore.sha(name + ":"
                    + args.path(name.equals("read_material_section") ? "sectionId" : "answerId").asText()
                    + ":" + result.path("sourceVersion").asText() + ":" + result.path("sha256").asText()
                    + ":" + result.path("content").asText().length()));
            for (var ref : result.path("references")) for (var chunk : ref.path("excerpts"))
                found.add(InterviewRunStore.sha(ref.path("id").asText() + ":" + ref.path("version").asInt()
                        + ":" + ref.path("sha256").asText() + ":" + chunk.path("chunkId").asText()
                        + ":" + chunk.path("startLine").asInt() + ":" + chunk.path("endLine").asInt()
                        + ":" + InterviewRunStore.sha(chunk.path("content").asText())));
            for (var item : result.path("items")) {
                // 本命令已筛选的历史观察中，每个标识只对应一个不可变修订。
                if (item.has("id")) found.add(InterviewRunStore.sha("observation:" + item.path("id").asText()));
                for (var id : item.path("observationIds"))
                    found.add(InterviewRunStore.sha("observation:" + id.asText()));
            }
        }
        int prior = keys.size();
        keys.addAll(found);
        int added = keys.size() - prior;
        int empty = added == 0 ? state.path("noChange").asInt() + 1 : 0;
        String decision = name.equals("search_knowledge") && state.path("lastSearchEmpty").asBoolean()
                ? "REVISE" : name.equals("read_answer_evidence") || memoryTool ? "VERIFY" : "RETRIEVE";
        if (name.equals("search_knowledge")) state.put("lastSearchEmpty", added == 0);
        // 重复动作或连续两次没有新增证据即停止取证；新增证据数量不表示回答质量提升。
        String stop = repeated || empty >= 2 ? "NO_PROGRESS" : null;
        state.put("steps", state.path("steps").asInt() + 1).put("noChange", empty);
        state.set("evidence", json.valueToTree(keys));
        state.set("actions", json.valueToTree(actions));
        if (stop != null) state.put("stopReason", stop);
        String outcome = result.path("status").asText();
        if (!java.util.Set.of("FOUND", "EMPTY", "MISSING", "INSUFFICIENT").contains(outcome)) outcome = "INSUFFICIENT";
        return new Observation(decision, outcome, added, before, fingerprint(keys, actions), actionKey,
                json.writeValueAsString(found), stop);
    }

    private static TreeSet<String> strings(JsonNode array) {
        var values = new TreeSet<String>();
        for (var value : array) values.add(value.asText());
        return values;
    }

    private static String fingerprint(TreeSet<String> evidence, TreeSet<String> actions) {
        return InterviewRunStore.sha(evidence + "\n" + actions);
    }
}
