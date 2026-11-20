package cn.edu.whu.zhizhida.knowledge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;

/** 编排资料检索、模型问答与历史保存；追问沿用前文的资料版本，以保持引用上下文一致。 */
@Service
public class KnowledgeQuestionService {
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "它|它们|这个|这些|这种|这类|该方案|该机制|该方法|该策略|上述|上面|前面|刚才|前者|后者|两者|^(?:那么|那|还有|为什么呢|为什么[？?]?$)");
    private final KnowledgeService documents;
    private final KnowledgeAnswerer ai;
    private final AiRequestQuota quota;
    private final CurrentUserProvider users;
    private final KnowledgeAnswerHistory history;

    public KnowledgeQuestionService(KnowledgeService documents, KnowledgeAnswerer ai, AiRequestQuota quota,
            CurrentUserProvider users, KnowledgeAnswerHistory history) {
        this.documents = documents;
        this.ai = ai;
        this.quota = quota;
        this.users = users;
        this.history = history;
    }

    public KnowledgeAnswer ask(KnowledgeAnswer.Request request) {
        var context = new ArrayList<KnowledgeAnswerRecord>();
        String parent = request.parentAnswerId();
        // 只加载最近六轮前文控制上下文长度；每条历史仍由存储层检查当前用户的归属。
        while (parent != null && context.size() < 6) {
            var previous = history.find(parent);
            context.addFirst(previous);
            parent = previous.parentAnswerId();
        }
        var snapshots = context.isEmpty() ? request.documentIds().stream().distinct().map(documents::find).toList()
                : context.getLast().documents().stream().map(s -> documents.version(s.documentId(), s.version()))
                        .toList();
        if (!context.isEmpty() && !new HashSet<>(request.documentIds())
                .equals(new HashSet<>(snapshots.stream().map(d -> d.id()).toList())))
            throw new UserInputException("追问须使用前文相同资料；更换资料请开始新问题。");
        String retrieval = retrievalQuestion(request.question(), context);
        var hits = documents.searchVersions(retrieval, snapshots, 6);
        KnowledgeAnswer answer;
        // 没有检索证据时直接返回资料不足，不消耗模型调用额度。
        if (hits.isEmpty())
            answer = DefaultKnowledgeAnswerer.insufficient();
        else {
            quota.admit(users.requireUserId().toString(), 2);
            answer = context.isEmpty() ? ai.answer(request.question(), hits)
                    : ai.answer(request.question(), hits, List.copyOf(context));
        }
        return context.isEmpty() ? history.save(request.question(), snapshots, answer)
                : history.save(request.question(), snapshots, answer, request.parentAnswerId());
    }

    /** 只为明显的指代或承接补问题上下文，生成的历史结论不能变成检索关键词。 */
    static String retrievalQuestion(String question, List<KnowledgeAnswerRecord> context) {
        if (context.isEmpty() || !FOLLOW_UP.matcher(question.strip()).find())
            return question;
        // 连续“它/这个”追问回溯至最近的明确问题，最多仍是当前问题加一条历史问题。
        String topic = context.getLast().question();
        for (int i = context.size() - 1; i >= 0; i--) {
            String previous = context.get(i).question();
            if (!FOLLOW_UP.matcher(previous.strip()).find()) {
                topic = previous;
                break;
            }
        }
        // 当前问题最多300个UTF-16单元；仅截短历史问题，使合并查询符合向量适配器的500上限。
        int end = Math.min(topic.length(), Math.max(0, 500 - question.length() - 1));
        if (end > 0 && end < topic.length() && Character.isHighSurrogate(topic.charAt(end - 1))
                && Character.isLowSurrogate(topic.charAt(end)))
            end--;
        return end == 0 ? question : question + " " + topic.substring(0, end);
    }
}
