package cn.edu.whu.zhizhida.interview.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import cn.edu.whu.zhizhida.common.execution.ToolRegistry.Registration;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 工具读取固定版本的材料、回答和知识，不依赖请求身份或实时来源查询。 */
@Component
@Order(0)
public final class LocalEvidenceToolProvider implements InterviewToolProvider {
    private final ObjectMapper json;
    private final KnowledgeCatalog knowledge;
    public LocalEvidenceToolProvider(ObjectMapper json, KnowledgeCatalog knowledge) {
        this.json = json;
        this.knowledge = knowledge;
    }
    @Override public List<Registration<Context, InterviewEvidenceTools.Result>> tools() {
        return List.of(
            registration("read_material_section", "仅在需要核对材料原文时，按 materialSections 目录中的 sectionId 读取固定版本片段；已有充分原文时无需调用，不能用来补读知识库任意结果。"
                        + "FOUND 返回片段前缀、完整片段 sha256 和 sourceVersion；MISSING 表示本场无此标识。maxChars 按 UTF-16 计数且不拆 emoji，"
                        + "truncated 表示返回前缀短于完整片段；偏移使用目录的 LF-v1 规范化文本及 start/end，sha256 不随前缀裁剪改变。",
                    new EvidenceToolArguments("sectionId", "maxChars", 80, 2000), this::readMaterial),
            registration("read_answer_evidence", "仅在需要核对回答证据时，按 answers 目录中的 answerId 读取本场用户回答；原文充分时无需调用，不得猜测缺失回答。"
                        + "FOUND 返回未经换行归一化的回答前缀和完整回答 sha256；MISSING 表示本场无此回答。"
                        + "maxChars 与引用偏移按 RAW_UTF16 计数且不拆 emoji，truncated 表示前缀裁剪；不得引用未返回的文本。",
                    new EvidenceToolArguments("answerId", "maxChars", 80, 2000), this::readAnswer),
            registration("search_knowledge", "有知识证据缺口时，仅检索本场所选知识的固定版本；资料已充分时无需调用，不搜索外部或未选资料。"
                        + "FOUND 返回带文档版本、sha256、chunkId 和原片段行范围的 references；EMPTY 表示无可返回片段，不代表相关知识不存在。"
                        + "topK 限制片段数，总正文最多 6000 个 UTF-16 代码单元且不拆 emoji；truncated 表示结果不完整，可能由条数、总预算或正文前缀裁剪造成。"
                        + "裁剪不改变原片段行范围或完整文档哈希，只能引用实际返回原文，不能推断遗漏内容。",
                    new EvidenceToolArguments("query", "topK", 500, 6), this::search));
    }
    private Registration<Context, InterviewEvidenceTools.Result> registration(String name, String description,
            EvidenceToolArguments arguments, java.util.function.BiFunction<Context, JsonNode, InterviewEvidenceTools.Result> handler) {
        String version = "local-evidence-v1";
        if (name.equals("search_knowledge")) {
            String profile = java.util.Objects.requireNonNullElse(knowledge.retrievalProfile(), "lexical-v1");
            version = "local-evidence-v2:" + profile;
        }
        return new Registration<>(arguments.definition(json, name, description), version, Set.of(),
                (context, args) -> arguments.validate(args), handler);
    }
    private InterviewEvidenceTools.Result readMaterial(Context context, JsonNode args) {
        var section = context.sections().stream().filter(s -> s.sectionId().equals(args.path("sectionId").asString())).findFirst();
        var root = json.createObjectNode();
        String value = null, hash = null;
        if (section.isPresent()) {
            value = section.get().content(); hash = section.get().sha256();
            root.put("sourceVersion", section.get().sourceVersion());
        }
        return prefix(root, value, hash, args.path("maxChars").asInt());
    }
    private InterviewEvidenceTools.Result readAnswer(Context context, JsonNode args) {
        int index = 0;
        String value = null;
        for (var turn : context.turns())
            if (turn.speaker().equals("user") && ("answer-" + (++index)).equals(args.path("answerId").asString()))
                value = turn.content();
        return prefix(json.createObjectNode(), value, value == null ? null : InterviewRunStore.sha(value), args.path("maxChars").asInt());
    }
    private InterviewEvidenceTools.Result prefix(tools.jackson.databind.node.ObjectNode root, String value, String hash, int maximum) {
        root.put("status", value == null ? "MISSING" : "FOUND");
        if (value != null) {
            int end = InterviewEvidenceTools.safeEnd(value, maximum);
            root.put("content", value.substring(0, end)).put("truncated", end < value.length()).put("sha256", hash);
        }
        return new InterviewEvidenceTools.Result(root, List.of());
    }
    private InterviewEvidenceTools.Result search(Context context, JsonNode args) {
        var root = json.createObjectNode();
        var ranked = knowledge.rank(context.knowledge(), args.path("query").asString());
        var selected = new ArrayList<KnowledgeCatalog.RankedExcerpt>();
        int topK = args.path("topK").asInt();
        int remaining = 6000;
        boolean truncated = false;
        for (var match : ranked) {
            if (selected.size() >= topK || remaining == 0) {
                truncated = true;
                break;
            }
            var excerpt = match.excerpt();
            int end = InterviewEvidenceTools.safeEnd(excerpt.content(), remaining);
            if (end < excerpt.content().length()) {
                truncated = true;
                excerpt = new KnowledgeSnapshot.Excerpt(excerpt.chunkId(), excerpt.heading(), excerpt.startLine(),
                        excerpt.endLine(), excerpt.content().substring(0, end));
            }
            if (end == 0) continue;
            remaining -= end;
            selected.add(new KnowledgeCatalog.RankedExcerpt(match.source(), excerpt));
        }
        var refs = KnowledgeCatalog.references(selected);
        root.put("status",refs.isEmpty()?"EMPTY":"FOUND").put("truncated",truncated);
        root.set("references",json.valueToTree(refs));
        return new InterviewEvidenceTools.Result(root, refs);
    }
}
