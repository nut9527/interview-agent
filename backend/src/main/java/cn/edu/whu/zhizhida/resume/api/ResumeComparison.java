package cn.edu.whu.zhizhida.resume.api;

import java.util.List;

public record ResumeComparison(
        ResumeVersionView left,
        ResumeVersionView right,
        int changedFieldCount,
        List<ResumeFieldDiff> fields
) {
}
