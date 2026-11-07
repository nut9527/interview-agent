package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 与指定轮次缺口关联的资料和练习建议；返回引用不代表已创建训练任务。 */
public record InterviewResources(int round, String competency, String gap,
        List<Passage> passages, List<Exercise> exercises) {
    public record Passage(String documentId, String title, int version, String chunkId,
            String heading, int startLine, int endLine, String content) { }
    public record Exercise(String id, int version, String title, InterviewDifficulty difficulty, String reason) { }
}
