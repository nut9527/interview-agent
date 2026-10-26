package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;

public record ResumeProfile(
        Long id,
        @NotBlank String ownerName,
        @NotBlank String targetRole,
        String summary,
        @NotNull List<String> skills,
        @NotNull List<String> educationExperiences,
        @NotNull List<String> projectExperiences,
        @NotNull List<String> internshipExperiences,
        @NotNull List<String> certificates,
        String templateKey,
        Long revision,
        @Valid ResumeDetails details
) {
    public ResumeProfile {
        templateKey = templateKey == null || templateKey.isBlank() ? "classic" : templateKey;
        revision = revision == null ? 0L : revision;
        details = details == null ? ResumeDetails.empty() : details;
    }

    // 兼容历史快照和内部创建简历的调用；修订号由数据库维护。
    public ResumeProfile(Long id, String ownerName, String targetRole, String summary,
                         List<String> skills, List<String> educationExperiences,
                         List<String> projectExperiences, List<String> internshipExperiences,
                         List<String> certificates, String templateKey) {
        this(id, ownerName, targetRole, summary, skills, educationExperiences,
                projectExperiences, internshipExperiences, certificates, templateKey, 0L);
    }

    public ResumeProfile withRevision(long value) {
        return new ResumeProfile(id, ownerName, targetRole, summary, skills, educationExperiences,
                projectExperiences, internshipExperiences, certificates, templateKey, value, details);
    }

    public ResumeProfile(Long id, String ownerName, String targetRole, String summary,
                         List<String> skills, List<String> educationExperiences,
                         List<String> projectExperiences, List<String> internshipExperiences,
                         List<String> certificates, String templateKey, Long revision) {
        this(id, ownerName, targetRole, summary, skills, educationExperiences, projectExperiences,
                internshipExperiences, certificates, templateKey, revision, null);
    }

    public ResumeProfile withDetails(ResumeDetails value) {
        return new ResumeProfile(id, ownerName, targetRole, summary, skills, educationExperiences,
                projectExperiences, internshipExperiences, certificates, templateKey, revision, value);
    }

    /** 为仍消费文本的推荐与面试模块提供兼容视图，不改变原始字段。 */
    public List<String> projectEvidenceTexts() {
        var values = new java.util.ArrayList<>(projectExperiences == null ? List.<String>of() : projectExperiences);
        details.projects().forEach(project -> values.add(project.name() + "："
                + String.join("；", project.contributions() == null ? List.of() : project.contributions())));
        return List.copyOf(values);
    }
}
