package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.*;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 简历优化的统一业务校验模板，真实模型与 模拟 实现共用同一输出约定。
 * 子类只负责生成 JSON，不可覆写的入口确保字段校验通过后才计为成功并返回候选文本。
 */
public abstract class StructuredResumeOptimizer implements AiResumeService {
    private final BusinessMetrics metrics;

    protected StructuredResumeOptimizer(BusinessMetrics metrics) { this.metrics = metrics; }

    protected abstract JsonNode generate(AiOptimizeRequest request);

    @Override
    public final AiOptimizeResponse optimize(AiOptimizeRequest request) {
        return metrics.measure(BusinessMetrics.Operation.RESUME_OPTIMIZE, () -> parse(generate(request)));
    }

    private AiOptimizeResponse parse(JsonNode payload) {
        String text = StructuredOutput.text(payload, "optimizedText", 12000);
        List<String> suggestions = StructuredOutput.texts(payload, "suggestions", 20, 1000);
        if (suggestions.isEmpty()) throw StructuredOutput.invalid("suggestions");
        List<ResumeImprovement> improvements = new ArrayList<>();
        for (JsonNode item : StructuredOutput.array(payload, "improvements", 20)) {
            improvements.add(new ResumeImprovement(
                    StructuredOutput.text(item, "category", 120), StructuredOutput.text(item, "issue", 1000),
                    StructuredOutput.text(item, "action", 1000), StructuredOutput.text(item, "example", 2000)));
        }
        return new AiOptimizeResponse(text, suggestions, List.copyOf(improvements),
                StructuredOutput.texts(payload, "targetKeywords", 50, 80),
                StructuredOutput.text(payload, "optimizationSummary", 1000));
    }
}
