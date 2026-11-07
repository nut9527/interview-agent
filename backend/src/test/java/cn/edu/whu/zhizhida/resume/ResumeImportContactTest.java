package cn.edu.whu.zhizhida.resume;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeImportContactTest {
    @Test
    void readsLabeledFieldsAndSkipsTemplateValues() {
        String template = "电话：xxxxxxx  |  邮箱：xxxxxx  |  所在城市：武汉市";
        String contacts = "Mobile：＋８６ １３８ ００００ ００００｜E-mail: demo@example.org";
        var fields = new ResumeImportContact();
        assertThat(fields.consume(template)).isTrue();
        assertThat(fields.consume(contacts)).isTrue();
        var contact = fields.result();

        assertThat(contact.phone()).isEqualTo("+86 138 0000 0000");
        assertThat(contact.email()).isEqualTo("demo@example.org");
        assertThat(contact.location()).isEqualTo("武汉市");
        assertThat(fields.consume("邮箱：second@example.org")).isFalse();
        var empty = new ResumeImportContact();
        assertThat(empty.consume("邮箱：____；手机：xxxx")).isTrue();
        assertThat(empty.result()).isNull();
    }

    @Test
    void retainsUnknownOrInvalidPartsForReview() {
        String mixed = "所在城市：武汉｜邮箱：demo@｜每周可实习四天";
        var fields = new ResumeImportContact();
        assertThat(fields.consume(mixed)).isFalse();
        var contact = fields.result();

        assertThat(contact.location()).isEqualTo("武汉");
        assertThat(contact.email()).isNull();
        assertThat(fields.consume("电话：-------")).isFalse();
        assertThat(fields.consume("维护邮箱：开发邮件处理模块")).isFalse();
    }
}
