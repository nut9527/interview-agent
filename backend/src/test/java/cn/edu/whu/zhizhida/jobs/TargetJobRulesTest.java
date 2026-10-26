package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.api.TargetJob.*;
import cn.edu.whu.zhizhida.jobs.domain.TargetJobRules;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TargetJobRulesTest {
    @Test void extractsSectionPriorityAndPreservesOriginalQuotes() {
        String jd = "任职要求：\n1. 熟悉 Java 和 MySQL\n2027 年毕业，本科及以上\n加分项：\n熟悉 Docker\n岗位职责：\n参与业务开发\n薪资面议";
        var extracted = TargetJobRules.extract(jd);
        assertThat(extracted.requirements()).extracting(Requirement::kind).containsExactly(Kind.MUST, Kind.MUST, Kind.BONUS, Kind.UNKNOWN, Kind.UNKNOWN);
        assertThat(extracted.requirements().get(1).text()).startsWith("2027");
        assertThat(extracted.requirements().get(1).keywords()).contains("本科", "毕业");
        assertThat(extracted.requirements()).allSatisfy(r -> assertThat(jd).contains(r.sourceText()));
        assertThat(extracted.warnings()).isNotEmpty();
    }
    @Test void separatesMixedBonusAndHandlesUnknownAndOptionalWording() {
        var extracted = TargetJobRules.extract("熟悉 Java，有 Redis 经验优先；必须具备沟通能力；工作地点待定；不要求 Docker；必须 Java 且 Python 优先");
        assertThat(extracted.requirements()).extracting(Requirement::kind).containsExactly(Kind.MUST, Kind.BONUS, Kind.MUST, Kind.UNKNOWN, Kind.BONUS, Kind.UNKNOWN);
    }
    @Test void preservesTechnicalNamesAndBoundsExtractionWithoutSilentLoss() {
        var extracted = TargetJobRules.extract("Requirements:\n.NET experience\nNode.js required\nPreferred qualifications:\nPython\nResponsibilities:\nBuild products");
        assertThat(extracted.requirements()).extracting(Requirement::kind).containsExactly(Kind.MUST, Kind.MUST, Kind.BONUS, Kind.UNKNOWN);
        assertThat(extracted.requirements().get(0).keywords()).contains(".net");
        assertThat(extracted.requirements().get(1).keywords()).contains("node.js");
        assertThat(TargetJobRules.extract("熟悉 Java\n".repeat(105)).requirements()).hasSize(100);
        assertThat(TargetJobRules.extract("熟悉 Java\n".repeat(105)).warnings()).anyMatch(w -> w.contains("100"));
        assertThat(TargetJobRules.extract("甲".repeat(1500)).requirements()).hasSize(2).allSatisfy(r -> assertThat(r.text().length()).isLessThanOrEqualTo(1000));
        assertThat(TargetJobRules.extract("\n  \n").requirements()).isEmpty();
    }
    @Test void tracesStructuredAndLegacyEvidenceWithoutClaimingSatisfaction() {
        var resume = resume().withDetails(new ResumeDetails("2027-06", "每周可实习三天", List.of(
                new ResumeDetails.Project("选课服务", "后端", null, "课程管理", List.of("使用 Java 实现接口"), List.of("MySQL"))),
                null, List.of(new ResumeDetails.Education("武汉大学", "软件工程", "本科", "2024-09", "2028-06"))));
        var requirements = List.of(new Requirement("1", "必须熟练 Java、MySQL 和 Redis", Kind.MUST, List.of("Java", "MySQL", "Redis"), null),
                new Requirement("2", "本科及以上，每周三天", Kind.MUST, List.of("本科", "每周"), null),
                new Requirement("3", "有沟通能力", Kind.UNKNOWN, List.of(), null));
        var result = TargetJobRules.analyze(job(requirements), resume);
        assertThat(result.requirements().get(0).status()).isEqualTo("REFERENCED");
        assertThat(result.requirements().get(0).unreferencedKeywords()).containsExactly("Redis");
        assertThat(result.requirements().get(0).evidence()).anySatisfy(e -> {
            assertThat(e.fieldPath()).isEqualTo("details.projects[0].contributions[0]");
            assertThat(e.quote()).isEqualTo("使用 Java 实现接口");
        });
        assertThat(result.requirements().get(1).evidence()).extracting(Evidence::fieldPath).contains("details.education[0].degree", "details.availability");
        assertThat(result.requirements().get(2).status()).isEqualTo("UNKNOWN");
        assertThat(result.unknownInformation()).contains("公司未填写", "来源未填写", "截止日期未说明");
        assertThat(result.resumeRevision()).isEqualTo(7L);
    }
    @Test void doesNotConfuseWordBoundariesNegationOrAbsentEvidenceWithFailure() {
        var requirements = List.of(new Requirement("1", "Java、Go、Redis", Kind.MUST, List.of("Java", "Go", "Redis"), null));
        var result = TargetJobRules.analyze(job(requirements), resume());
        assertThat(result.requirements().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(result.requirements().get(0).evidence()).isEmpty();
        assertThat(result.requirements().get(0).unreferencedKeywords()).containsExactly("Java", "Go", "Redis");
    }
    private ResumeProfile resume() {
        return new ResumeProfile(3L, "测试", "Java 后端", "熟悉 JavaScript、Django。未使用过 Redis", List.of(), List.of(), List.of(), List.of(), List.of(), "classic", 7L);
    }
    private TargetJob job(List<Requirement> requirements) {
        return new TargetJob("job-1", 2, "后端实习", "", "", "", null, "测试 JD", requirements, Instant.EPOCH);
    }
}
