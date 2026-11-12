package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.StructuredOutput;

/** 评价内容未通过校验，可进入有限输出修正；供应商通信及持久化故障不属于此类。 */
public final class InterviewReviewRejected extends UpstreamServiceException {
    private final StructuredOutput.RejectionReason reason;
    private final String fieldPath;

    public InterviewReviewRejected(String reason) {
        this(reason, StructuredOutput.RejectionReason.SEMANTIC_MISMATCH, "$");
    }

    InterviewReviewRejected(StructuredOutput.FieldValidationException rejected) {
        this(rejected.getMessage(), rejected.reason(), rejected.fieldPath());
    }

    InterviewReviewRejected(String message, StructuredOutput.RejectionReason reason, String fieldPath) {
        super(message);
        this.reason = reason;
        this.fieldPath = StructuredOutput.diagnosticField(fieldPath);
    }

    public StructuredOutput.RejectionReason reason() { return reason; }
    public String fieldPath() { return fieldPath; }

    /** 修复指令只携带协议字段与固定约束，不反射任意异常内容或模型字段名。 */
    public String repairFeedback() {
        return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("code", reason.name()).put("path", fieldPath).put("constraint", repairConstraint()).toString();
    }

    private String repairConstraint() {
        if (reason == StructuredOutput.RejectionReason.SEMANTIC_MISMATCH) {
            // 已审查的典型语义失败保留精确说明；其他原因仍由完整契约约束，不把动态文本带回提示。
            return switch (getMessage()) {
                case "观察与证据状态不一致", "证据不足的项不能标为已观察" ->
                        "OBSERVED 必须有合法引文且 claimStatus 不能为 INSUFFICIENT；其他状态使用空引文和 INSUFFICIENT。";
                case "引文偏移无效或拆开 emoji", "引文与指定偏移原文不一致" ->
                        "引文必须逐字等于固定原文 RAW_UTF16 半开区间，偏移不越界且不得拆开代理对。";
                case "anchor 引用结构无效，不能混用偏移协议", "anchor 不属于本轮固定来源或版本",
                        "anchor 引文为空或超限", "anchor 片段不含逐字引文", "anchor 片段内引文不唯一，请提供更长引文",
                        "anchor 引文拆开 UTF-16 代理对" ->
                        "从本轮 citationAnchors 选择相同 sourceKey 的 anchorId 和片段内逐字唯一的 quote；重复时提供更长引文，禁止混用偏移或改写原文。";
                case "对照来源不在本场固定范围", "技术判断缺少独立的对照资料，不能仅依据回答自述" ->
                        "仅引用本轮固定目录；技术维度 OBSERVED 需要回答与对照资料引文，否则保持 NOT_OBSERVED。";
                case "最终评价不能将待核对维度改为确定结论", "重大纠错必须先完成反证检查",
                        "重大纠错需要检索资料反证，回答自述不足以证明技术错误" ->
                        "遵守本轮 evidenceReview 的支持状态；证据不足保留 INSUFFICIENT，不输出 majorCorrection。";
                default -> "完整输出必须满足本轮 allowedActions、allowedProbes、能力目标、证据引用和独立观察约束；focus 给出未重复的验证主张与期望证据；无法核实的内容保持未知。";
            };
        }
        return switch (reason) {
            case MISSING_FIELD -> "补齐该必需字段；字段类型及条件见服务端字段契约。";
            case TYPE_MISMATCH -> "使用字段契约声明的 JSON 类型和枚举；禁止将字符串、数字、布尔值互相转换。";
            case EMPTY_VALUE -> "该字段必须提供非空白文本。";
            case LIMIT_EXCEEDED -> "遵守字段契约中的 UTF16 文本上限和集合数量上限；引用仍须逐字匹配原文。";
            case INVALID_JSON -> "返回单个完整合法 JSON 对象，不含 Markdown 或额外文本。";
            default -> "使用字段契约允许的值；引用和动作还须满足本轮证据及路由约束。";
        };
    }
}
