package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 面试使用的固定版本知识正文与片段；后续编辑不改变此快照，使用资格仍须单独复核。 */
public record KnowledgeSnapshot(String id, String title, String sourceName, int version, String sha256, String content,
        List<Excerpt> excerpts) {
    public record Reference(String id, String title, int version, String sha256, List<Excerpt> excerpts) {
        public Reference {
            excerpts = List.copyOf(excerpts);
        }
    }

    public record Excerpt(String chunkId, String heading, int startLine, int endLine, String content) {
    }
}
