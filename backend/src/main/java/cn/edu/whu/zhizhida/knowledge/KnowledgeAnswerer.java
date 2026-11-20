package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;

interface KnowledgeAnswerer {
    KnowledgeAnswer answer(String question, List<Hit> hits);

    default KnowledgeAnswer answer(String question, List<Hit> hits,
            List<cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord> context) {
        return answer(question, hits);
    }
}
