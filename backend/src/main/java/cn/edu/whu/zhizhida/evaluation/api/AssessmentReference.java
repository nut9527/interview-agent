package cn.edu.whu.zhizhida.evaluation.api;

/** 由服务端提供的可信、长度受限的归档参考资料，不从模型输出或 HTTP 输入直接反序列化。 */
public record AssessmentReference(String key, long ownerId, String sourceType, String sourceId,
        String version, String text, String sha256, AssessmentResult.Normalization normalization,
        AssessmentResult.Validity validity) { }
