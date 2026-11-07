package cn.edu.whu.zhizhida.resume.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 简历历史版本与当时的内容快照；恢复操作仍须通过当前简历的修订检查。 */
public record ResumeVersionView(
        Long id,
        Long resumeId,
        int versionNumber,
        String changeType,
        String changeNote,
        LocalDateTime createdAt,
        ResumeProfile snapshot
) {

    /** 简历版本列表摘要，不携带完整历史正文。 */
    @Schema(name = "cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary")
    public record ResumeVersionSummary(Long id, Long resumeId, int versionNumber,
            String changeType, String changeNote, LocalDateTime createdAt) {}
}
