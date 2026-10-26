package cn.edu.whu.zhizhida.jobs.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TargetJob(String id, long revision, String title, String company, String sourceName,
                        String sourceUrl, LocalDate deadline, String jdText,
                        List<Requirement> requirements, Instant updatedAt) {
    public enum Kind { MUST, BONUS, UNKNOWN }

    /** sourceText 保留 岗位说明 引文；用户新增的要求可以不带引文。 */
    public record Requirement(
            @NotBlank @Size(max = 60) String id,
            @NotBlank @Size(max = 1000) String text,
            @NotNull Kind kind,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> keywords,
            @Size(max = 1000) String sourceText) {}

    public record Extraction(List<Requirement> requirements, List<String> warnings, String ruleVersion) {}
    public record ExtractRequest(@NotBlank @Size(max = 20000) String jdText) {}
    public record AnalyzeRequest(@NotNull @Positive Long resumeId, @NotNull @PositiveOrZero Long revision) {}
    public record Summary(String id, long revision, String title, String company, String sourceName,
                          LocalDate deadline, Instant updatedAt) {}
    public record Evidence(String fieldPath, String label, String quote, List<String> keywords) {}
    public record RequirementMatch(Requirement requirement, String status, List<Evidence> evidence,
                                   List<String> unreferencedKeywords) {}
    public record Analysis(String targetJobId, long targetJobRevision, Long resumeId, Long resumeRevision,
                           String ruleVersion, List<RequirementMatch> requirements, List<String> unknownInformation) {}
}
