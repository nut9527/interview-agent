package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Question;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate;
import java.util.List;

interface KnowledgeQuizGenerator {
    record Result(List<Question> questions, String executionMode) { }
    default Result generate(List<Hit> sources,int count) { return generate(sources,new Generate(List.of(),count)); }
    Result generate(List<Hit> sources, Generate request);
}
