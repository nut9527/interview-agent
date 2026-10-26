package cn.edu.whu.zhizhida.resume.api;

import java.util.List;

public record AiOptimizeResponse(
        String optimizedText,
        List<String> suggestions,
        List<ResumeImprovement> improvements,
        List<String> targetKeywords,
        String optimizationSummary
) {
    public AiOptimizeResponse(String optimizedText, List<String> suggestions) {
        this(optimizedText, suggestions, List.of(), List.of(), "");
    }
}
