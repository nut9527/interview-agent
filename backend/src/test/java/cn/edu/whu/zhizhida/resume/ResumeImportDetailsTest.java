package cn.edu.whu.zhizhida.resume;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeImportDetailsTest {
    @Test
    void structuresOnlyCompleteEducationAndKeepsOtherSourceLines() {
        var education = List.of(
                "示例大学　软件工程｜本科　2022.09–2026.06",
                "另一所大学　计算机科学｜硕士　2028年6月毕业",
                "示例学院　软件工程｜本科　2026-03 至 2024-07");
        var result = ResumeImportDetails.extract(null, education, List.of());
        assertThat(result.details().education()).singleElement().satisfies(row -> {
            assertThat(row.school()).isEqualTo("示例大学");
            assertThat(row.major()).isEqualTo("软件工程");
            assertThat(row.startDate()).isEqualTo("2022-09");
            assertThat(row.endDate()).isEqualTo("2026-06");
        });
        assertThat(result.details().graduationDate()).isEqualTo("2028-06");
        assertThat(result.educationText()).containsExactly(education.get(1), education.get(2));
    }

    @Test
    void separatesProjectsAndRetainsUnstructuredNotes() {
        var result = ResumeImportDetails.extract(null, List.of(), List.of(
                "独立开发过工具", "校园服务平台 2025.02–2025.11", "角色：后端开发",
                "技术栈：Java / Spring Boot", "项目简介：预约服务", "实现并发预约",
                "项目名称：命令行工具", "技术栈：Go、SQLite", "实现导出"));
        assertThat(result.projectText()).containsExactly("独立开发过工具");
        assertThat(result.details().projects()).hasSize(2);
        var first = result.details().projects().getFirst();
        assertThat(first.name()).isEqualTo("校园服务平台");
        assertThat(first.role()).isEqualTo("后端开发");
        assertThat(first.contributions()).containsExactly("实现并发预约");
        assertThat(result.details().projects().getLast().techStack()).containsExactly("Go", "SQLite");
    }

    @Test
    void keepsSourceBlockWhenItExceedsStructuredFieldLimits() {
        var lines = new ArrayList<>(List.of("大项目 2020.01–至今"));
        lines.addAll(Collections.nCopies(31, "贡献描述"));
        var result = ResumeImportDetails.extract(null, List.of(), lines);
        assertThat(result.details().projects()).isEmpty();
        assertThat(result.projectText()).containsExactlyElementsOf(lines);
    }
}
