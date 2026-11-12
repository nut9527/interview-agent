package cn.edu.whu.zhizhida.common;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class JsonFingerprintTest {
    @Test void sortedJsonV1KeepsThePublishedByteFormat() {
        var json = new JsonMapper();
        var input = json.readTree("""
                {"空":[],"b":{"z":1.25,"a":"引号\\\"\\n\\t"},"a":[2,1,null,true]}
                """);
        assertThat(JsonFingerprint.sha(input))
                .isEqualTo("46b796b75f789a9892c38d8cd6d808b9e013219b7571e63580d90217e4bdb2b6");
    }
}
