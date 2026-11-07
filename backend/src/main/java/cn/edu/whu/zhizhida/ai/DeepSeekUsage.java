package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import tools.jackson.databind.JsonNode;

/** JSON 与工具对话共用供应商用量协议；缺失或非法用量不记作零消耗。 */
final class DeepSeekUsage {
    private DeepSeekUsage() { }

    static ModelUsage parse(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return ModelUsage.unknown(ModelUsage.Status.MISSING);
        }
        JsonNode input = node.path("prompt_tokens");
        JsonNode output = node.path("completion_tokens");
        JsonNode total = node.path("total_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber() || !total.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong() || !total.canConvertToLong()) {
            return ModelUsage.unknown(ModelUsage.Status.INVALID);
        }
        try {
            return new ModelUsage(ModelUsage.Status.REPORTED, input.longValue(), output.longValue(), total.longValue());
        } catch (IllegalArgumentException ex) {
            return ModelUsage.unknown(ModelUsage.Status.INVALID);
        }
    }
}
