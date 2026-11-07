package cn.edu.whu.zhizhida.resume.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 仅提交当前草稿中可润色的文字；条目在本次请求中的位置就是其标识。 */
public record ResumeDraftOptimizeRequest(
        @Size(max = 120) String targetRole,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Entry> entries
) {
    public record Entry(@NotNull Kind kind, @NotBlank @Size(max = 4000) String text,
                        @Size(max = 120) String projectName) {}

    public enum Kind {
        @JsonProperty("summary") SUMMARY(4000),
        @JsonProperty("skill") SKILL(80),
        @JsonProperty("projectBackground") PROJECT_BACKGROUND(2000),
        @JsonProperty("projectContribution") PROJECT_CONTRIBUTION(3000),
        @JsonProperty("projectExperience") PROJECT_EXPERIENCE(3000),
        @JsonProperty("internshipExperience") INTERNSHIP_EXPERIENCE(3000);

        private final int maxLength;

        Kind(int maxLength) { this.maxLength = maxLength; }

        public int maxLength() { return maxLength; }
    }
}
