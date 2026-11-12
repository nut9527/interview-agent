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
    // 当前版本模板包的正文哈希，包含首尾换行。
    private static final Map<String, String> VERSIONED_HASHES = Map.of(
            "knowledgeOpening", "28615cf1b0dc05c34ffd1d4f1b970256e5c2ad4d6da92b1b161ad3cd8e3a91dd",
            "routing", "63d27747fba65fd92f748983f896544e60fde54c040b6b1fd8ee9abd9694865a",
            "adaptiveRouting", "eaef47f5f9606d65414fc5c5091f99d944e3c633a3f0eb6bd31ee277e82e8bb2",
            "opening", "202c14a04e105c0687be9d1d1f92d142dfe4ca1131263d6c1abfc26f1becc69a",
            "review", "2d95ec348842aba8eb4bfda194ff979b1de62ef6090e14084723983fa544a7b5",
            "repair", "c89ca36be3ee034b1bfba1e2c89f86fcf3b045bfae46b62a87bd590a22a4041e",
            "assessment", "092de7dc36c49cda47ff5bc5d9a4615920116586550e52ac334eeb089786b81f",
            "agent", "cf9b2f7601c29c5f46ed825e064f6d7401254cad41a644c1bb5997adbff48483");

    @TempDir Path directory;

    @Test
    void bundledTemplatesMatchVersionedHashes() throws Exception {
        var templates = InterviewPromptTemplates.defaults();
        assertThat(templates.version()).isEqualTo("interview-prompts-v6");
        for (var expected : VERSIONED_HASHES.entrySet()) {
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
        for (String name : VERSIONED_HASHES.keySet())
            Files.writeString(directory.resolve(name + ".txt"), defaults.get(name), StandardCharsets.UTF_8);
    }
}
