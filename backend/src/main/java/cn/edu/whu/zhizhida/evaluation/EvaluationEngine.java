package cn.edu.whu.zhizhida.evaluation;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentDraft;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import java.time.Instant;
import java.util.*;

/**
 * 校验评价草稿的结构和引文位置，生成绑定固定来源版本的证据。
 *
 * <p>调用方负责提供账号及有效来源快照；本组件执行确定性校验，不查询身份、调用模型或持久化。
 * 校验通过表示引文能够定位到原文，观察的语义支持度与技术正确性由独立复核处理。
 */
@org.springframework.stereotype.Component
public final class EvaluationEngine implements cn.edu.whu.zhizhida.evaluation.api.AssessmentEvaluator {
    public static final String VERSION = "answer-evidence-v1";
    public static final RubricSnapshot RUBRIC = new RubricSnapshot("qualitative-v1", List.of(
            new Anchor(Dimension.RESPONSIBILITY, "明确陈述本人承担的行动与职责边界；团队成果不自动视为个人贡献"),
            new Anchor(Dimension.CAUSAL_EXPLANATION, "解释具体约束、采取行动的理由或验证关系；只罗列术语不算因果解释")));
    /**
     * 保存证据来源的账号、版本、正文和有效状态。
     *
     * <p>规范化方式决定引文的坐标系：LF_V1 先统一换行，RAW_UTF16 保留原文。
     * 引文偏移始终按保存后的 Java 字符串 UTF-16 单元计算。
     */
    public record Source(long ownerId, String sourceType, String sourceId, String version,
            Normalization normalization, String text, Instant observedAt, Validity validity) {
        public Source {
            Objects.requireNonNull(normalization);
            Objects.requireNonNull(text);
            Objects.requireNonNull(observedAt);
            if (normalization == Normalization.LF_V1) text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
    }

    @Override
    public AssessmentResult evaluateAnswer(long owner, String sourceId, String answer, Instant observedAt,
            AssessmentDraft draft, String mode) {
        return evaluateAnswer(owner, sourceId, answer, observedAt, draft, mode, List.of());
    }

    /**
     * 为本轮回答建立固定来源，并按草稿内容生成基础评价和六维分项评价。
     *
     * <p>回答原文按 UTF-8 计算版本哈希，引文偏移按 UTF-16 单元计量；
     * 正文变化同时改变证据版本，分项评价使用调用方提供的本场对照资料范围。
     */
    @Override
    public AssessmentResult evaluateAnswer(long owner, String sourceId, String answer, Instant observedAt,
            AssessmentDraft draft, String mode, List<cn.edu.whu.zhizhida.evaluation.api.AssessmentReference> references) {
        var source = new Source(owner, "ANSWER_SELF_REPORT", sourceId, hash(answer),
                Normalization.RAW_UTF16, answer, observedAt, Validity.VALID);
        var result = evaluate(owner, source, draft, mode);
        return draft == null || draft.detail() == null ? result : result.withDetail(
                new DetailedEvaluationEngine(this).evaluate(owner, source, references, draft.detail()));
    }

    public AssessmentResult evaluate(long owner, Source source, AssessmentDraft draft, String mode) {
        require(owner > 0 && owner == source.ownerId(), "来源不属于当前账号");
        require(source.validity() == Validity.VALID, "来源已失效");
        require(source.sourceId() != null && !source.sourceId().isBlank()
                && source.version() != null && !source.version().isBlank(), "来源缺少固定版本");
        require("ANSWER_SELF_REPORT".equals(source.sourceType()), "此评价协议只接受本轮回答自述");
        require(wellFormed(source.text()), "来源含无效 UTF-16 字符");
        boolean legacyMissing = draft == null || draft.items() == null && draft.detail() != null;
        var proposed = legacyMissing ? List.<AssessmentDraft.Item>of() : draft.items();
        require(proposed != null && proposed.size() == 2 || legacyMissing, "assessment 必须包含两个独立维度");
        var seen = EnumSet.noneOf(Dimension.class);
        var evidence = new LinkedHashMap<String, Evidence>();
        var items = new ArrayList<AssessmentItem>();
        for (var item : proposed) {
            require(item != null && item.dimension() != null && seen.add(item.dimension()), "评价维度无效或重复");
            require(item.status() != null && item.claimStatus() != null, "评价状态缺失");
            require(text(item.claimText(), 300) && text(item.limitation(), 300), "观察与限制必须为有界文本");
            require(item.quotes() != null && item.quotes().size() <= 2, "每项最多两条引文");
            require(item.claimStatus() != ClaimStatus.CONTRADICTED, "本轮自述不足以作重大技术纠错");
            boolean observed = item.status() == Observation.OBSERVED;
            require(observed ? !item.quotes().isEmpty() : item.quotes().isEmpty(), "未观察/不适用不能伪造证据，已观察必须有引文");
            require(observed || item.claimStatus() == ClaimStatus.INSUFFICIENT, "未观察项保持证据不足");
            require(!observed || item.claimStatus() != ClaimStatus.INSUFFICIENT, "证据不足的项不能标为已观察");
            var ids = new LinkedHashSet<String>();
            for (var quote : item.quotes()) {
                var verified = cite(owner, source, quote);
                evidence.putIfAbsent(verified.evidenceId(), verified);
                ids.add(verified.evidenceId());
            }
            var claim = new Claim(hash(source.sourceId() + "\n" + source.version() + "\n" + item.dimension()
                    + "\n" + item.claimText() + "\n" + ids), item.claimText(), List.copyOf(ids), item.claimStatus(),
                    "MODEL_PROPOSED", item.limitation());
            items.add(new AssessmentItem(item.dimension(), item.status(), claim));
        }
        // 旧结果缺少独立分项时保留未知，不从总评或其他字段反推出观察结论。
        if (legacyMissing) for (var dimension : Dimension.values()) items.add(new AssessmentItem(dimension,
                Observation.NOT_OBSERVED, new Claim(hash(source.sourceId() + source.version() + dimension),
                "未提供独立分项观察", List.of(), ClaimStatus.INSUFFICIENT, "NOT_EVALUATED", "旧协议结果不能反推独立维度或评分")));
        return new AssessmentResult(VERSION, RUBRIC, mode, List.copyOf(evidence.values()), items,
                "引文位置已核对；观察由模型提出，语义支持与技术正确性尚未独立复核。回答自述不证明实际经历；不生成总分。");
    }

    /**
     * 将引文绑定到同一账号下的有效来源版本和精确原文区间。
     *
     * <p>区间采用 UTF-16 左闭右开偏移，端点须避开代理对内部，并逐字匹配原文；
     * 不通过文本搜索重新定位，以保留模型所指向的具体出现位置。
     */
    Evidence cite(long owner, Source source, AssessmentDraft.Quote quote) {
        require(owner > 0 && owner == source.ownerId(), "来源不属于当前账号");
        require(source.validity() == Validity.VALID, "来源已失效");
        require(source.sourceId() != null && !source.sourceId().isBlank() && source.version() != null && !source.version().isBlank(), "来源缺少固定版本");
        require(wellFormed(source.text()), "来源含无效 UTF-16 字符");
        require(quote != null && text(quote.quote(), 500), "引文为空或超限");
        int start = quote.offsetStart(), end = quote.offsetEnd();
        require(start >= 0 && end > start && end <= source.text().length()
                && boundary(source.text(), start) && boundary(source.text(), end), "引文偏移无效或拆开 emoji");
        require(source.text().substring(start, end).equals(quote.quote()), "引文与指定偏移原文不一致");
        String sha = hash(source.text());
        // 证据标识绑定账号、来源版本、规范化方式和区间，防止相同引文跨来源复用。
        String id = hash(owner + "\n" + source.sourceType() + "\n" + source.sourceId() + "\n"
                + source.version() + "\n" + source.normalization() + "\n" + sha + "\n" + start + ":" + end);
        return new Evidence(id, owner, source.sourceType(), source.sourceId(), source.version(), sha,
                source.normalization(), start, end, quote.quote(), source.observedAt(), source.validity());
    }

    /**
     * 核对已有证据是否仍与调用方提供的有效来源快照一致。
     *
     * <p>复用要求账号、来源类型、版本、规范化方式、全文哈希和引文区间均匹配。
     * 调用方先确认来源访问权限；在其他位置找到相同文本不能替代原证据绑定。
     */
    public boolean valid(long owner, Source source, Evidence evidence) {
        if (owner != source.ownerId() || owner != evidence.ownerId() || source.validity() != Validity.VALID
                || evidence.validity() != Validity.VALID || !Objects.equals(source.sourceId(), evidence.sourceId())
                || !Objects.equals(source.sourceType(), evidence.sourceType()) || !Objects.equals(source.version(), evidence.sourceVersion())
                || source.normalization() != evidence.normalizationVersion() || !wellFormed(source.text())
                || !hash(source.text()).equals(evidence.sourceHash())) return false;
        int start = evidence.offsetStart(), end = evidence.offsetEnd();
        return start >= 0 && end > start && end <= source.text().length() && boundary(source.text(), start)
                && boundary(source.text(), end) && source.text().substring(start, end).equals(evidence.quote());
    }

    public static String hash(String value) {
        return Sha256.hex(value);
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static boolean boundary(String text, int offset) {
        return offset == 0 || offset == text.length() || !Character.isHighSurrogate(text.charAt(offset - 1))
                || !Character.isLowSurrogate(text.charAt(offset));
    }
    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
