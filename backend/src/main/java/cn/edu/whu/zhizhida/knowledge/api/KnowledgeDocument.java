package cn.edu.whu.zhizhida.knowledge.api;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

/** 个人资料当前版本的正文与分段；读写权限由资料服务校验，片段偏移用于原文定位。 */
public record KnowledgeDocument(String id, String title, String sourceName, int version, String content,
                                Instant updatedAt, List<Chunk> chunks) {
    public record Summary(String id, String title, String sourceName, int version, Instant updatedAt) { }
    public record Chunk(String id, int ordinal, String heading, int startOffset, int endOffset,
                        int startLine, int endLine, String content) { }
    public record Hit(String documentId, String title, String sourceName, int version, Chunk chunk, int score) { }
    public record Create(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=2000000) String content) { }
    public record Rename(@NotBlank @Size(max=160) String title) { }
    public record Edit(@NotBlank @Size(max=2000000) String content, @NotNull @Min(1) Integer expectedVersion) { }
    public record Restore(@NotNull @Min(1) Integer expectedVersion) { }
}
