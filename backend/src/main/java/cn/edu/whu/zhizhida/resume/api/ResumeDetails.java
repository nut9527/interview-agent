package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;

/** 可选结构化补充；历史文本保留。未知信息使用空值，不推断不符合要求。 */
public record ResumeDetails(
        @Size(max = 120) String graduationDate,
        @Size(max = 200) String availability,
        @Valid @Size(max = 20) List<@NotNull Project> projects,
        @Valid Contact contact,
        @Valid @Size(max = 10) List<@NotNull Education> education
) {
    public ResumeDetails {
        projects = projects == null ? List.of() : List.copyOf(projects);
        education = education == null ? List.of() : List.copyOf(education);
    }

    public ResumeDetails(String graduationDate, String availability, List<Project> projects) {
        this(graduationDate, availability, projects, null, List.of());
    }

    public static ResumeDetails empty() { return new ResumeDetails(null, null, List.of()); }

    public record Contact(
            @Size(max = 40) @Pattern(regexp = "^$|[+0-9() -]{6,40}", message = "请输入有效的联系电话") String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 120) String location,
            @Valid @Size(max = 8) List<@NotNull Link> links
    ) {
        public Contact { links = links == null ? List.of() : List.copyOf(links); }
    }

    public record Link(
            @NotBlank @Size(max = 80) String label,
            @NotBlank @Size(max = 1000)
            @Pattern(regexp = "(?i)^https?://[^\\s/]+(?:/[^\\s]*)?$", message = "链接必须是完整的 HTTP/HTTPS 地址") String url
    ) {}

    public record Education(
            @NotBlank @Size(max = 160) String school,
            @NotBlank @Size(max = 160) String major,
            @NotBlank @Size(max = 80) String degree,
            @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String startDate,
            @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])|至今") String endDate
    ) {
        @AssertTrue(message = "教育结束时间不能早于开始时间")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isPeriodValid() {
            return startDate == null || endDate == null || endDate.equals("至今")
                    || endDate.compareTo(startDate) >= 0;
        }
    }

    public record Project(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 120) String role,
            @Size(max = 120) String period,
            @Size(max = 2000) String background,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 3000) String> contributions,
            @Size(max = 30) List<@NotBlank @Size(max = 80) String> techStack
    ) {
        public Project { techStack = techStack == null ? List.of() : List.copyOf(techStack); }
        public Project(String name, String role, String period, String background, List<String> contributions) {
            this(name, role, period, background, contributions, List.of());
        }
    }
}
