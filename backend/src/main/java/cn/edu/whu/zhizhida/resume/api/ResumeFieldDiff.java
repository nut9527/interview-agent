package cn.edu.whu.zhizhida.resume.api;

import java.util.List;

public record ResumeFieldDiff(
        String field,
        String label,
        List<String> leftValues,
        List<String> rightValues,
        List<String> added,
        List<String> removed,
        boolean changed
) {
}
