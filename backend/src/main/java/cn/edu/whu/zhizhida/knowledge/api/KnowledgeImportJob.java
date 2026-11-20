package cn.edu.whu.zhizhida.knowledge.api;

import java.time.Instant;

/** 后台导入进度与到期信息；解析就绪不等于已确认创建资料。 */
public record KnowledgeImportJob(String id, String sourceName, String status, int completedPages,
        int totalPages, String message, Instant expiresAt, String documentId) {
}
