package cn.edu.whu.zhizhida.evaluation;

import cn.edu.whu.zhizhida.evaluation.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Relation;
import java.util.*;

/**
 * 校验六维评价的来源完整性、引文关系和冲突状态。
 *
 * <p>回答自述用于定位候选人表现，固定资料用于提供技术对照；两类来源分别校验。
 * 结构和引文校验不作语义裁定，存在反证或重大技术纠错的结论进入争议状态。
 */
final class DetailedEvaluationEngine {
    static final String VERSION = "six-dimensions-v1";
    static final DetailedAssessment.Rubric RUBRIC = new DetailedAssessment.Rubric("behavioral-six-v1", List.of(
            new DetailedAssessment.Anchor(Dimension.EVIDENCE_SUFFICIENCY, "提供可核对行动、约束、结果或限制；陈述具体不等于已经验证"),
            new DetailedAssessment.Anchor(Dimension.TECHNICAL_CORRECTNESS, "针对具体技术断言对照固定资料；引文可定位与技术结论正确分别处理，争议保持未知"),
            new DetailedAssessment.Anchor(Dimension.CAUSAL_EXPLANATION, "解释约束、行动理由和验证关系；术语罗列不等于因果解释"),
            new DetailedAssessment.Anchor(Dimension.TRADEOFFS, "比较条件内的备选方案及接受的代价；不要求唯一标准答案"),
            new DetailedAssessment.Anchor(Dimension.RESPONSIBILITY, "区分本人行动、团队成果与职责边界；不推断未陈述的实际经历"),
            new DetailedAssessment.Anchor(Dimension.EXPRESSION, "说明对象、步骤与限定条件；不按回答长度、修辞或术语数量评分")));
    private final EvaluationEngine citations;
    DetailedEvaluationEngine(EvaluationEngine citations) { this.citations = citations; }

    /**
     * 将六维草稿转换为带固定证据链接的评价结果。
     *
     * <p>来源键只在本轮回答和调用方提供的资料目录中解析；技术正确性维度的已观察结论须同时引用回答和对照资料。
     * 每个维度独立保留观察状态与引文关系，共享的证据按来源和区间去重。
     */
    DetailedAssessment evaluate(long owner, EvaluationEngine.Source answer, List<AssessmentReference> references, DetailedAssessmentDraft draft) {
        require(VERSION.equals(draft.protocolVersion()), "分项协议版本不兼容");
        require(draft.items() != null && draft.items().size() == Dimension.values().length, "必须包含六个独立评价维度");
        require(references != null && references.size() <= 6, "对照来源超出范围");
        // answer 保留给本轮作答，外部资料不能覆盖这一来源入口。
        var scope = new HashMap<String, AssessmentReference>();
        for (var ref : references) {
            require(ref != null && ref.key() != null && !ref.key().isBlank() && !"answer".equals(ref.key())
                    && scope.putIfAbsent(ref.key(), ref) == null, "来源键缺失或重复");
        }
        var seen = EnumSet.noneOf(Dimension.class);
        var evidence = new LinkedHashMap<String, Evidence>();
        var items = new ArrayList<DetailedAssessment.Item>();
        for (var item : draft.items()) {
            require(item != null && item.dimension() != null && seen.add(item.dimension()), "评价维度无效或重复");
            require(item.status() != null && item.claimStatus() != null && text(item.claimText(), 300) && text(item.limitation(), 300), "分项观察、状态或限制无效");
            require(item.citations() != null && item.citations().size() <= 3, "每个维度最多三条引文");
            boolean observed = item.status() == Observation.OBSERVED;
            boolean technical = item.dimension() == Dimension.TECHNICAL_CORRECTNESS;
            require(!item.majorCorrection() || technical && observed, "技术纠错必须属于已观察的技术维度");
            require(item.claimStatus() != ClaimStatus.CONTRADICTED || item.majorCorrection(), "反驳结论必须显式标注待复核的技术纠错");
            require(observed ? !item.citations().isEmpty() && item.claimStatus() != ClaimStatus.INSUFFICIENT
                    : item.citations().isEmpty() && item.claimStatus() == ClaimStatus.INSUFFICIENT, "观察与证据状态不一致");
            boolean hasAnswer = false, hasReference = false, conflict = false;
            var links = new ArrayList<DetailedAssessment.Citation>();
            var ids = new LinkedHashSet<String>();
            for (var citation : item.citations()) {
                require(citation != null && citation.relation() != null, "引文关系缺失");
                EvaluationEngine.Source source;
                if ("answer".equals(citation.sourceKey())) {
                    require(citation.relation() == Relation.SELF_REPORT, "回答自述不能伪装成独立技术依据");
                    hasAnswer = true; source = answer;
                } else {
                    var ref = scope.get(citation.sourceKey());
                    require(ref != null, "对照来源不在本场固定范围");
                    require(citation.relation() != Relation.SELF_REPORT, "参考资料不能证明本人已经实施");
                    require(ref.ownerId() == owner && ref.validity() == Validity.VALID, "对照来源越权或失效");
                    require(Set.of("KNOWLEDGE_EXCERPT", "QUESTION_REFERENCE").contains(ref.sourceType()), "对照来源类型无效");
                    require(ref.normalization() == Normalization.RAW_UTF16 && ref.text() != null
                            && EvaluationEngine.hash(ref.text()).equals(ref.sha256()), "对照来源版本哈希不一致");
                    source = new EvaluationEngine.Source(owner, ref.sourceType(), ref.sourceId(), ref.version(), ref.normalization(), ref.text(), answer.observedAt(), ref.validity());
                    hasReference = true; conflict |= citation.relation() == Relation.COUNTEREVIDENCE;
                }
                var e = citations.cite(owner, source, new AssessmentDraft.Quote(citation.offsetStart(), citation.offsetEnd(), citation.quote()));
                evidence.putIfAbsent(e.evidenceId(), e); ids.add(e.evidenceId());
                links.add(new DetailedAssessment.Citation(e.evidenceId(), citation.relation()));
            }
            require(!observed || hasAnswer, "观察候选人表现必须引用本轮回答");
            require(!technical || !observed || hasReference, "技术判断缺少独立的对照资料，不能仅依据回答自述");
            // 存在反证或重大技术纠错时保留争议，不能将模型判断升级为正式裁定。
            ClaimStatus status = conflict || item.majorCorrection() ? ClaimStatus.DISPUTED : item.claimStatus();
            String limitation = item.limitation() + (conflict || item.majorCorrection() ? "；来源对照或纠错仍有争议，未经独立事实复核，不作正式正确/错误裁定" : "");
            var claim = new Claim(EvaluationEngine.hash(answer.sourceId() + "\n" + answer.version() + "\n" + item.dimension()
                    + "\n" + item.claimText() + "\n" + ids), item.claimText(), List.copyOf(ids), status, "MODEL_PROPOSED", limitation);
            items.add(new DetailedAssessment.Item(item.dimension(), item.status(), claim, item.majorCorrection(),
                    technical ? observed ? "REVIEW_REQUIRED" : "INSUFFICIENT" : "NOT_APPLICABLE", links));
        }
        return new DetailedAssessment(VERSION, RUBRIC, List.copyOf(evidence.values()), items,
                "六项为独立定性观察，不生成总分。已核验来源身份、版本与引文位置；语义与技术判断仍待独立复核，参考资料不等于候选人的实际经历。");
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
