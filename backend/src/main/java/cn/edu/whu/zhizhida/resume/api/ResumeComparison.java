package cn.edu.whu.zhizhida.resume.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 两侧版本及逐字段差异，只描述比较结果，不执行合并或恢复。 */
public record ResumeComparison(
        ResumeVersionView left,
        ResumeVersionView right,
        int changedFieldCount,
        List<ResumeFieldDiff> fields
) {

    /** 单个字段的左右值、增删项与变化标记，供差异审阅使用。 */
    @Schema(name = "cn.edu.whu.zhizhida.resume.api.ResumeFieldDiff")
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

    /** 指定比较两侧的简历或版本，实际读取前分别校验账号归属。 */
    @Schema(name = "cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest")
    public record ResumeCompareRequest(
            @Valid @NotNull ResumeReference left,
            @Valid @NotNull ResumeReference right
    ) {
    }

    /** 指向简历及可选历史版本；省略版本时读取当前简历，不能据此绕过归属校验。 */
    @Schema(name = "cn.edu.whu.zhizhida.resume.api.ResumeReference")
    public record ResumeReference(
            @NotNull Long resumeId,
            Long versionId
    ) {
    }
}
