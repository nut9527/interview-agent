package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Question;

interface KnowledgeQuizGenerator {
    record Result(List<Question> questions, String executionMode) {
    }

    default Result generate(List<Hit> sources, int count) {
        return generate(sources, new Generate(List.of(), count));
    }

    Result generate(List<Hit> sources, Generate request);
}
