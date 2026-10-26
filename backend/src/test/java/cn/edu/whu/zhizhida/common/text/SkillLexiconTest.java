package cn.edu.whu.zhizhida.common.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class SkillLexiconTest {
    @ParameterizedTest
    @CsvSource({"JavaScript,Java", "Django,Go", "NoSQL,SQL", "C++,C", "C#,C", "Gmail,AI",
            "不会Java,Java", "未使用过 Redis,Redis", "no experience with Python,Python", "not familiar with Go,Go"})
    void rejectsSubstringAndNegatedSkills(String text, String skill) {
        assertThat(SkillLexicon.contains(text, skill)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"使用Java开发接口,Java", "Java17开发服务,Java", "Java.,Java", "SpringBoot开发服务,Spring Boot", "K8s部署,Kubernetes",
            "Postgres数据库,PostgreSQL", "Golang微服务,Go", "Ｃ＋＋,C++", "熟悉C#开发,C#",
            "REST接口设计,接口设计", "使用dotnet开发,.NET", "使用nodejs开发,Node.js"})
    void matchesAliasesAndMixedLanguageEvidence(String text, String skill) {
        assertThat(SkillLexicon.contains(text, skill)).isTrue();
    }
}
