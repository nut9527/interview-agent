package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MockAiResumeService extends StructuredResumeOptimizer {
    private static final Pattern MOCK_KEYWORD_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])(?:Spring\\s*Boot|Spring\\s*Cloud|MyBatis(?:-Plus)?|"
                    + "TypeScript|JavaScript|TensorFlow|PyTorch|Kubernetes|K8s|Docker|Redis|MySQL|"
                    + "Python|React|Vue(?:\\.js|3)?|Java|Prompt|Linux|Git|RESTful|REST|SQL|AI)"
                    + "(?![A-Za-z0-9])|大模型|模型训练|接口设计|接口开发|联调",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper mapper;

    public MockAiResumeService(ObjectMapper mapper, BusinessMetrics metrics) {
        super(metrics);
        this.mapper = mapper;
    }

    @Override
    protected JsonNode generate(AiOptimizeRequest request) {
        String optimizedText = buildMockOptimizedText(request);
        return mapper.valueToTree(new AiOptimizeResponse(
                optimizedText,
                List.of(
                        "补充与本段经历直接相关且可核验的规模、效率、质量或成果信息；没有数据时不要虚构。",
                        "明确个人动作、实际使用的方法和真实结果；仅填写原文中确实发生的内容。",
                        "当前为 mock 优化模式，正文只做保守改写；配置 AI_API_KEY 后可使用模型进行语义优化。"
                ),
                buildMockImprovements(request),
                extractKeywordsFromRaw(request.rawText()),
                "Mock 模式仅规范原文格式并保留已有事实，未补充原文未出现的技术、经历或数据。"
        ));
    }

    private String buildMockOptimizedText(AiOptimizeRequest request) {
        String raw = request.rawText().trim();
        String normalized = raw.replaceAll("\\s+", " ");
        return normalized.matches(".*[。！？.!?]$") ? normalized : normalized + "。";
    }

    private List<ResumeImprovement> buildMockImprovements(AiOptimizeRequest request) {
        String raw = request.rawText();
        List<ResumeImprovement> improvements = new ArrayList<>();
        improvements.add(new ResumeImprovement(
                "表达结构",
                "Mock 模式不推断原文之外的事实，当前仅做保守格式整理。",
                "在不改变事实的前提下，按“个人动作—实际方法—真实结果”重排原文；缺失信息由用户补充。",
                "[原文中的个人动作] + [原文中的实际方法] + [原文中的真实结果]"
        ));
        if (!raw.matches(".*\\d+.*")) {
            improvements.add(new ResumeImprovement(
                    "成果量化",
                    "缺少规模、效率或质量指标，成果说服力偏弱。",
                    "补充真实可核验的数据；没有数据时先保留待填写提示，不虚构结果。",
                    "完成 [请填写真实数量] 项工作，使 [请填写真实指标] 达到 [请填写真实结果]。"
            ));
        }
        improvements.add(new ResumeImprovement(
                "岗位匹配",
                "Mock 模式不推断原文未出现的岗位技能，原文与目标岗位的关联需要人工确认。",
                "仅从原文已有事实中选择与目标岗位相关的内容，不要为了匹配岗位添加未使用的技术。",
                "从原文中选取真实技能或成果，说明其与“" + request.targetRole().trim() + "”岗位的关系。"
        ));
        return improvements;
    }

    private List<String> extractKeywordsFromRaw(String rawText) {
        List<String> keywords = new ArrayList<>();
        Matcher matcher = MOCK_KEYWORD_PATTERN.matcher(rawText);
        while (matcher.find()) {
            String keyword = matcher.group().replaceAll("\\s+", " ").trim();
            boolean alreadyIncluded = keywords.stream().anyMatch(existing -> existing.equalsIgnoreCase(keyword));
            if (!alreadyIncluded) {
                keywords.add(keyword);
            }
        }
        return List.copyOf(keywords);
    }

}
