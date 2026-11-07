package cn.edu.whu.zhizhida.evaluation.api;

import java.time.Instant;

/**
 * 对已接收回答和模型提案做确定性校验；调用方负责验证账号权限并提供可信来源，模型不能指定来源身份。
 * 引文使用原文 UTF-16 半开区间，校验通过只表示来源与结构一致，不证明语义判断或技术结论正确。
 */
public interface AssessmentEvaluator {
    /** 无独立参考资料的入口；缺失的旧版分项保持未观察，不能反推为已验证结论。 */
    AssessmentResult evaluateAnswer(long owner, String sourceId, String answer, Instant observedAt,
            AssessmentDraft draft, String executionMode);
    /** 对照资料必须由服务端固定版本与哈希；提案的维度、状态或引用违反协议时抛出 IllegalArgumentException。 */
    AssessmentResult evaluateAnswer(long owner, String sourceId, String answer, Instant observedAt,
            AssessmentDraft draft, String executionMode, java.util.List<AssessmentReference> references);
}
