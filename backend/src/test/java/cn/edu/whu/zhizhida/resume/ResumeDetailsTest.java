package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeDetailsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldJsonStillLoadsWithoutNewFields() throws Exception {
        var details = mapper.readValue("""
            {"graduationDate":"2028-06","projects":[{"name":"旧项目","contributions":["实现登录"]}]}
            """, ResumeDetails.class);
        assertThat(details.contact()).isNull();
        assertThat(details.education()).isEmpty();
        assertThat(details.projects().get(0).techStack()).isEmpty();
    }

    @Test
    void deliveryFieldsRoundTripWithoutSyntheticValidationProperty() throws Exception {
        var details = details("+86 138 0000 0000", "name@example.com", "https://example.com/work?q=java", "2028-06");
        String json = mapper.writeValueAsString(details);
        assertThat(mapper.readValue(json, ResumeDetails.class)).isEqualTo(details);
        assertThat(json).doesNotContain("periodValid");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(details)).isEmpty();
        }
    }

    @Test
    void rejectsMalformedContactUnsafeLinksAndReversedEducationDates() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(details("abcdef", "bad-email", "javascript:alert(1)", "2020-06"));
            assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                    .contains("contact.phone", "contact.email", "contact.links[0].url", "education[0].periodValid");
        }
    }

    @Test
    void draftsMayOmitContactAndEducationAndCurrentEducationAcceptsPresent() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(ResumeDetails.empty())).isEmpty();
            assertThat(factory.getValidator().validate(details("", "", "http://example.com", "至今"))).isEmpty();
        }
    }

    @Test
    void validatesRequiredEducationFieldsAndTechnologyLength() {
        var details = new ResumeDetails(null, null,
                List.of(new ResumeDetails.Project("项目", null, null, null, List.of(), List.of("x".repeat(81)))), null,
                List.of(new ResumeDetails.Education("", "", "", "2024-13", "2028-00")));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(details)).hasSize(6);
        }
    }

    private ResumeDetails details(String phone, String email, String url, String endDate) {
        return new ResumeDetails(null, null,
                List.of(new ResumeDetails.Project("求职平台", "后端开发", "2026.07 - 至今", "求职工具",
                        List.of("实现版本恢复"), List.of("Java", "MySQL"))),
                new ResumeDetails.Contact(phone, email, "武汉", List.of(new ResumeDetails.Link("作品集", url))),
                List.of(new ResumeDetails.Education("武汉大学", "软件工程", "本科", "2024-09", endDate)));
    }
}
