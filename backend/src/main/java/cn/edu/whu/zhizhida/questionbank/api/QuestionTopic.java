package cn.edu.whu.zhizhida.questionbank.api;

/** 题目主题标识保持稳定；能力标识与后端面试能力目录一致。 */
public enum QuestionTopic {
    JAVA("Java 基础", "foundation"),
    DATABASE("数据库与事务", "storage"),
    REDIS("Redis 与缓存", "storage"),
    CONCURRENCY("并发控制", "concurrency"),
    PERFORMANCE("性能分析", "performance"),
    RELIABILITY("工程可靠性", "reliability");

    private final String label;
    private final String competencyId;
    QuestionTopic(String label, String competencyId) { this.label = label; this.competencyId = competencyId; }
    public String label() { return label; }
    public String competencyId() { return competencyId; }
}

