package cn.edu.whu.zhizhida.resume.api;

import java.time.LocalDateTime;

public record ResumeVersionSummary(Long id, Long resumeId, int versionNumber,
        String changeType, String changeNote, LocalDateTime createdAt) {}
