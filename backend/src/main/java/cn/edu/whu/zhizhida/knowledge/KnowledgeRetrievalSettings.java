package cn.edu.whu.zhizhida.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 检索排序参数不改变向量索引内容；修改参数只使检索缓存失效。 */
@Component
public final class KnowledgeRetrievalSettings {
    private final int rrfK;
    private final int lexicalTopN;
    private final int vectorTopN;
    private final int maxPerHeading;

    public KnowledgeRetrievalSettings() {
        this(60, 30, 30, 2);
    }

    @Autowired
    public KnowledgeRetrievalSettings(
            @Value("${agent.retrieval.rrf-k:60}") int rrfK,
            @Value("${agent.retrieval.lexical-top-n:30}") int lexicalTopN,
            @Value("${agent.retrieval.vector-top-n:30}") int vectorTopN,
            @Value("${agent.retrieval.max-per-heading:2}") int maxPerHeading) {
        this.rrfK = bounded("rrf-k", rrfK, 1000);
        this.lexicalTopN = bounded("lexical-top-n", lexicalTopN, 100);
        this.vectorTopN = bounded("vector-top-n", vectorTopN, 100);
        this.maxPerHeading = bounded("max-per-heading", maxPerHeading, 20);
    }

    private static int bounded(String name, int value, int maximum) {
        if (value < 1 || value > maximum)
            throw new IllegalArgumentException("agent.retrieval." + name + " must be between 1 and " + maximum);
        return value;
    }

    public int rrfK() {
        return rrfK;
    }

    public int lexicalTopN() {
        return lexicalTopN;
    }

    public int vectorTopN() {
        return vectorTopN;
    }

    public int maxPerHeading() {
        return maxPerHeading;
    }

    public String fingerprint() {
        return "bm25-rrf-sections-v4:k" + rrfK + ":l" + lexicalTopN + ":v" + vectorTopN + ":h" + maxPerHeading;
    }
}
