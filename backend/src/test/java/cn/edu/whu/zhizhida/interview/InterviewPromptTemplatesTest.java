package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InterviewPromptTemplatesTest {
    // 迁移前由 Java 编译器求值的常量哈希，包含 text block 的缩进处理与首尾换行。
    private static final Map<String, String> ORIGINAL_HASHES = Map.of(
            "knowledgeOpening", "28615cf1b0dc05c34ffd1d4f1b970256e5c2ad4d6da92b1b161ad3cd8e3a91dd",
            "routing", "63d27747fba65fd92f748983f896544e60fde54c040b6b1fd8ee9abd9694865a",
            "adaptiveRouting", "458b2fa0901c673383d2d8eaca965e544d92e65172084bdf2b3913c71861b32d",
            "opening", "202c14a04e105c0687be9d1d1f92d142dfe4ca1131263d6c1abfc26f1becc69a",
            "review", "8b2422f5688d821ab6f7803f2e2e3a82d5b4a929b5cd7c410aac5318973f54a7",
            "repair", "c89ca36be3ee034b1bfba1e2c89f86fcf3b045bfae46b62a87bd590a22a4041e",
            "assessment", "3e4c2828a0eb817e84f745091041753a57451d7f9e54792ad40675e6b7b8950f",
            "agent", "9611cfa449ed823d42b6c6ae8cdd44ef4af05be62fb7dd85b948d3d01805d28a");

    @TempDir Path directory;

    @Test
    void bundledTemplatesPreserveEveryOriginalByte() throws Exception {
        var templates = InterviewPromptTemplates.defaults();
        assertThat(templates.version()).isEqualTo("interview-prompts-v2");
        for (var expected : ORIGINAL_HASHES.entrySet()) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(templates.get(expected.getKey()).getBytes(StandardCharsets.UTF_8)));
            assertThat(hash).as(expected.getKey()).isEqualTo(expected.getValue());
        }
        assertThat(InterviewPromptTemplates.load(null).get("opening")).isEqualTo(templates.get("opening"));
        assertThat(InterviewPromptTemplates.load("  ").version()).isEqualTo(templates.version());
        assertThatThrownBy(() -> templates.get("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalPackageLoadsAsOneImmutableSnapshot() throws IOException {
        copyPackage();
        Files.writeString(directory.resolve("version.txt"), "  team-prompts-v2\n", StandardCharsets.UTF_8);
        String opening = "\n外部模板：只输出问题。\n";
        Files.writeString(directory.resolve("opening.txt"), opening, StandardCharsets.UTF_8);
        var templates = InterviewPromptTemplates.load(directory.toString());
        assertThat(templates.version()).isEqualTo("team-prompts-v2");
        assertThat(templates.get("opening")).isEqualTo(opening);
        assertThat(templates.get("review")).isEqualTo(InterviewPromptTemplates.defaults().get("review"));

        Files.writeString(directory.resolve("version.txt"), "team-prompts-v3", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("opening.txt"), "另一模板", StandardCharsets.UTF_8);
        assertThat(templates.version()).isEqualTo("team-prompts-v2");
        assertThat(templates.get("opening")).isEqualTo(opening);
        var reloaded = InterviewPromptTemplates.load(directory.toString());
        assertThat(reloaded.version()).isEqualTo("team-prompts-v3");
        assertThat(reloaded.get("opening")).isEqualTo("另一模板");
    }

    @ParameterizedTest
    @ValueSource(strings = {"version.txt", "opening.txt", "review.txt", "agent.txt", "assessment.txt",
            "repair.txt", "knowledgeOpening.txt", "routing.txt", "adaptiveRouting.txt"})
    void incompletePackagesFailWithoutFallingBack(String file) throws IOException {
        copyPackage();
        Files.delete(directory.resolve(file));
        assertThatThrownBy(() -> InterviewPromptTemplates.load(directory.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot read").hasMessageContaining(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"version.txt", "opening.txt", "routing.txt"})
    void blankFilesFailBeforeServingRequests(String file) throws IOException {
        copyPackage();
        Files.writeString(directory.resolve(file), " \n\t", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> InterviewPromptTemplates.load(directory.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Empty").hasMessageContaining(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no placeholders", "%s", "%s %s %s", "%s %d", "%s %s %", "%1$s %2$s"})
    void routingRequiresExactlyTheTwoSupportedPlaceholders(String routing) throws IOException {
        copyPackage();
        Files.writeString(directory.resolve("routing.txt"), routing, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> InterviewPromptTemplates.load(directory.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("routing.txt").hasMessageContaining("two %s");
    }

    @Test
    void routingAllowsEscapedLiteralPercentSigns() throws IOException {
        copyPackage();
        Files.writeString(directory.resolve("routing.txt"), "%s：100%%；probe=%s", StandardCharsets.UTF_8);
        var templates = InterviewPromptTemplates.load(directory.toString());
        assertThat(templates.get("routing").formatted("ADVANCE", "NONE")).isEqualTo("ADVANCE：100%；probe=NONE");
    }

    private void copyPackage() throws IOException {
        var defaults = InterviewPromptTemplates.defaults();
        Files.writeString(directory.resolve("version.txt"), defaults.version(), StandardCharsets.UTF_8);
        for (String name : ORIGINAL_HASHES.keySet())
            Files.writeString(directory.resolve(name + ".txt"), defaults.get(name), StandardCharsets.UTF_8);
    }
}
