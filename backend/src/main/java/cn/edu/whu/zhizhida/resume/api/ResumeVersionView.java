package cn.edu.whu.zhizhida.resume.api;

import java.time.LocalDateTime;

public record ResumeVersionView(
        Long id,
        Long resumeId,
        int versionNumber,
        String changeType,
        String changeNote,
        LocalDateTime createdAt,
        ResumeProfile snapshot
) {
}
