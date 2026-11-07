package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.common.execution.ToolExecutionGuard;
import cn.edu.whu.zhizhida.common.execution.ToolRegistry;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.execution.InterviewToolProvider.Context;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.Observation;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Selection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 工具仅处理传入的固定材料，不访问请求身份、外部网址、实时文档或文件系统。 */
@Component
public class InterviewEvidenceTools {
    public record Section(
            String sectionId,
            String type,
            String sourceVersion,
            String normalization,
            int start,
            int end,
            String sha256,
            boolean truncated,
            String content) {}

    public record Result(JsonNode payload, List<KnowledgeSnapshot.Reference> references) {}

    private final ObjectMapper json;
    private final KnowledgeCatalog knowledge;
    private static final int MAX_READ_CHARS = 2000;
    private final ToolRegistry<Context, Result> registry;
    private final ToolExecutionGuard guard;

    @Autowired
    public InterviewEvidenceTools(
            ObjectMapper json,
            KnowledgeCatalog knowledge,
            List<InterviewToolProvider> providers,
            ToolExecutionGuard guard) {
        this.json = json;
        this.knowledge = knowledge;
        this.guard = guard;
        this.registry =
                new ToolRegistry<>(
                        providers.stream().flatMap(provider -> provider.tools().stream()).toList());
    }

    public InterviewEvidenceTools(
            ObjectMapper json, KnowledgeCatalog knowledge, List<InterviewToolProvider> providers) {
        this(json, knowledge, providers, new ToolExecutionGuard());
    }

    public InterviewEvidenceTools(ObjectMapper json, KnowledgeCatalog knowledge) {
        this(
                json,
                knowledge,
                List.of(
                        new LocalEvidenceToolProvider(json, knowledge),
                        new PracticeEvidenceToolProvider(json)));
    }

    public List<Section> sections(InterviewSession s) {
        var result = new ArrayList<Section>();
        material(
                result,
                "resume",
                s.resumeSnapshot(),
                s.resumeRevision() == null ? "archived" : s.resumeRevision().toString(),
                s.resumeSnapshot() != null && s.resumeSnapshot().length() >= 12000);
        material(result, "jd", s.jobSnapshot(), "archived", false);
        if (s.preparation() != null)
            material(
                    result,
                    "project",
                    json.writeValueAsString(s.preparation().projectSnapshot()),
                    "archived",
                    false);
        return List.copyOf(result);
    }

    /** 先统一换行再按 UTF-16 字符位置切片，使片段偏移与摘要对应同一份规范化文本。 */
    public static void material(
            List<Section> out, String type, String raw, String version, boolean truncated) {
        if (raw == null || raw.isEmpty()) return;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        for (int start = 0; start < text.length(); ) {
            int end = safeEnd(text, start + MAX_READ_CHARS);
            out.add(
                    new Section(
                            type + "-" + start,
                            type,
                            version,
                            "LF-v1",
                            start,
                            end,
                            InterviewRunStore.sha(text.substring(start, end)),
                            truncated,
                            text.substring(start, end)));
            start = end;
        }
    }

    /** 偏移仍是 UTF-16 代码单元；仅在有效代理对中间的切点向前退一位。 */
    static int safeEnd(String text, int maximum) {
        int end = Math.min(text.length(), maximum);
        if (end > 0
                && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }

    public JsonNode manifest(InterviewSession s, List<InterviewTurn> turns) {
        return manifest(s, turns, List.of());
    }

    /** 目录仅暴露可读取条目的标识和元数据，正文由受限工具按需提供。 */
    public JsonNode manifest(
            InterviewSession s, List<InterviewTurn> turns, List<Section> additional) {
        var root = json.createObjectNode();
        var list = root.putArray("materialSections");
        for (var section : allSections(s, additional)) {
            var node = (ObjectNode) json.valueToTree(section);
            node.remove("content");
            list.add(node);
        }
        var answers = root.putArray("answers");
        int n = 0;
        for (var turn : turns)
            if (turn.speaker().equals("user"))
                answers.addObject()
                        .put("answerId", "answer-" + (++n))
                        .put("length", turn.content().length());
        var docs = root.putArray("knowledge");
        for (var d : knowledge(s))
            docs.addObject()
                    .put("id", d.id())
                    .put("title", d.title())
                    .put("version", d.version())
                    .put("sha256", d.sha256());
        return root;
    }

    public List<JsonNode> definitions() {
        return definitions(Selection.none());
    }

    public List<JsonNode> definitions(Selection memory) {
        return registry.definitions(scopes(memory));
    }

    public List<JsonNode> allDefinitions() {
        return registry.allDefinitions();
    }

    public List<java.util.Map<String, Object>> registryManifest() {
        return registry.manifest();
    }

    public boolean requiresMemory(String name) {
        return registry.requiresScope(name, InterviewToolProvider.MEMORY_SCOPE);
    }

    private Set<String> scopes(Selection memory) {
        return memory.available() ? Set.of(InterviewToolProvider.MEMORY_SCOPE) : Set.of();
    }

    public void validate(String name, JsonNode args) {
        validate(name, args, Selection.none());
    }

    public void validate(String name, JsonNode args, Selection memory) {
        registry.validate(name, args, scopes(memory), Context.validation(memory));
    }

    public List<Section> allSections(InterviewSession session, List<Section> additional) {
        var all = new ArrayList<>(sections(session));
        all.addAll(additional);
        return List.copyOf(all);
    }

    public Result execute(
            InterviewSession session, List<InterviewTurn> turns, String name, JsonNode args) {
        return execute(session, turns, name, args, List.of());
    }

    public Result execute(
            InterviewSession session,
            List<InterviewTurn> turns,
            String name,
            JsonNode args,
            List<Section> additional) {
        return execute(session, turns, name, args, additional, Selection.none(), List.of());
    }

    public Result execute(
            InterviewSession session,
            List<InterviewTurn> turns,
            String name,
            JsonNode args,
            List<Section> additional,
            Selection memory,
            List<Observation> qualified) {
        return execute(session, turns, name, args, additional, memory, qualified, Long.MAX_VALUE);
    }

    public Result execute(
            InterviewSession session,
            List<InterviewTurn> turns,
            String name,
            JsonNode args,
            List<Section> additional,
            Selection memory,
            List<Observation> qualified,
            long remainingMillis) {
        // 构造材料上下文前拒绝无效调用，实际分发前再次校验。
        validate(name, args, memory);
        return executeBounded(
                name,
                args,
                memory,
                new Context(
                        allSections(session, additional),
                        turns,
                        knowledge(session),
                        memory,
                        qualified),
                remainingMillis);
    }

    public JsonNode history(
            Selection memory, List<Observation> qualified, String name, JsonNode args) {
        if (!requiresMemory(name)) throw new RunFailureException("TOOL_DENIED");
        validate(name, args, memory);
        return executeBounded(
                        name,
                        args,
                        memory,
                        new Context(List.of(), List.of(), List.of(), memory, qualified),
                        Long.MAX_VALUE)
                .payload();
    }

    private Result executeBounded(
            String name, JsonNode args, Selection memory, Context context, long remainingMillis) {
        var limits = registry.limits(name);
        try {
            return guard.call(
                    Math.min(limits.timeoutMillis(), remainingMillis),
                    () -> {
                        var result = registry.execute(name, args, scopes(memory), context);
                        if (result == null || result.references() == null)
                            throw new RunFailureException("TOOL_RESULT_INVALID");
                        return new Result(
                                checkedPayload(name, result.payload()),
                                List.copyOf(result.references()));
                    });
        } catch (RunFailureException ex) {
            if (ex.code().equals("TOOL_TIMEOUT") && remainingMillis < limits.timeoutMillis())
                throw new RunFailureException("RUN_TOOL_BUDGET");
            throw ex;
        }
    }

    /** 缓存回执同样重新校验，不通过截断引文、偏移或 JSON 适配限额。 */
    public JsonNode checkedPayload(String name, JsonNode payload) {
        if (payload == null || !payload.isObject())
            throw new RunFailureException("TOOL_RESULT_INVALID");
        if (payload.toString().length() > registry.limits(name).maxResultChars())
            throw new RunFailureException("TOOL_RESULT_BUDGET");
        return payload.deepCopy();
    }

    public List<KnowledgeSnapshot> knowledge(InterviewSession s) {
        return s.agent() == null
                        || s.agent().training() == null
                        || s.agent().training().knowledge() == null
                ? List.of()
                : s.agent().training().knowledge();
    }

    public List<KnowledgeSnapshot.Reference> fixed(InterviewSession s, String query) {
        return knowledge.select(knowledge(s), query);
    }

    public List<KnowledgeSnapshot.Reference> verifiedExcerpts(
            InterviewSession session, List<KnowledgeSnapshot.Reference> receipts) {
        return knowledge.verifiedExcerpts(knowledge(session), receipts);
    }
}
