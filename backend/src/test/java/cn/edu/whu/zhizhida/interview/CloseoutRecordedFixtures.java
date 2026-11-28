package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** R3 原始录制只读入口：两场运行、三对请求响应；不改写来源 ID、anchor 或模型正文。 */
final class CloseoutRecordedFixtures {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String PREFIX = "closeout-1-61f33cef289b7aea";

    static List<JsonNode> report() throws Exception {
        return lines(".jsonl", "cec3a4ecbbbeef7036d237871b8bb4479e25800d205448b962b92c7ee7a19ef9");
    }

    static List<JsonNode> wire() throws Exception {
        return lines(
                ".wire.jsonl", "b3ae0d47ae5cd499008c7fbba6565c5915292b5b938c7f63923d262b757faf8d");
    }

    private static List<JsonNode> lines(String suffix, String sha256) throws Exception {
        String raw = Files.readString(Path.of("../docs/evidence", PREFIX + suffix));
        assertThat(EvaluationEngine.hash(raw)).isEqualTo(sha256);
        return raw.lines().map(JSON::readTree).toList();
    }

    static JsonNode prompt(JsonNode request) {
        return JSON.readTree(
                request.path("messages")
                        .get(1)
                        .path("content")
                        .asString()
                        .split("\n本场证据目录：", 2)[0]);
    }

    static List<JsonNode> exchange(int sample) throws Exception {
        var rows = wire();
        return sample == 0 ? rows.subList(0, 2) : rows.subList(2, 6);
    }
}
