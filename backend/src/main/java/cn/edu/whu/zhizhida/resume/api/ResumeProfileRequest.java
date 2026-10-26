package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.Valid;

import java.util.List;

public record ResumeProfileRequest(
        @NotBlank String ownerName,
        @NotBlank String targetRole,
        String summary,
        List<String> skills,
        List<String> educationExperiences,
        List<String> projectExperiences,
        List<String> internshipExperiences,
        List<String> certificates,
        String templateKey,
        @PositiveOrZero Long revision,
        @Valid ResumeDetails details
) {
    public ResumeProfileRequest(String ownerName, String targetRole, String summary,
                                List<String> skills, List<String> educationExperiences,
                                List<String> projectExperiences, List<String> internshipExperiences,
                                List<String> certificates, String templateKey, Long revision) {
        this(ownerName, targetRole, summary, skills, educationExperiences, projectExperiences,
                internshipExperiences, certificates, templateKey, revision, null);
    }
    public ResumeProfileRequest(String ownerName, String targetRole, String summary,
                                List<String> skills, List<String> educationExperiences,
                                List<String> projectExperiences, List<String> internshipExperiences,
                                List<String> certificates, String templateKey) {
        this(ownerName, targetRole, summary, skills, educationExperiences, projectExperiences,
                internshipExperiences, certificates, templateKey, null);
    }
}
