package cn.edu.whu.zhizhida.interview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动时完整加载的模板快照；生成请求和审计指纹始终使用同一份内容。 */
final class InterviewPromptTemplates {
    private static final String RESOURCE_DIRECTORY = "/prompts/interview/v1/";
    private static final List<String> NAMES = List.of(
            "opening", "review", "agent", "assessment", "repair",
            "knowledgeOpening", "routing", "adaptiveRouting");

    private final String version;
    private final Map<String, String> templates;

    private InterviewPromptTemplates(String version, Map<String, String> templates) {
        this.version = version;
        this.templates = Map.copyOf(templates);
    }

    static InterviewPromptTemplates defaults() {
        return read(file -> {
            try (var stream = InterviewPromptTemplates.class.getResourceAsStream(RESOURCE_DIRECTORY + file)) {
                if (stream == null) throw new IOException("Missing classpath resource: " + RESOURCE_DIRECTORY + file);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    static InterviewPromptTemplates load(String directory) {
        if (directory == null || directory.isBlank()) return defaults();
        final Path root;
        try {
            root = Path.of(directory);
        } catch (InvalidPathException ex) {
            throw new IllegalStateException("Invalid interview prompt directory", ex);
        }
        // 指定外部目录后整包读取；不逐文件回退，避免悄悄混用不同版本的提示词。
        return read(file -> Files.readString(root.resolve(file), StandardCharsets.UTF_8));
    }

    String version() {
        return version;
    }

    String get(String name) {
        var value = templates.get(name);
        if (value == null) throw new IllegalArgumentException("Unknown interview prompt: " + name);
        return value;
    }

    private static InterviewPromptTemplates read(TemplateReader reader) {
        String version = required(reader, "version.txt").strip();
        var templates = new LinkedHashMap<String, String>();
        for (String name : NAMES) templates.put(name, required(reader, name + ".txt"));
        // %% 交给 String.formatted 输出字面百分号，其余占位符只能是两个顺序 %s。
        String routing = templates.get("routing").replace("%%", "");
        String withoutPlaceholders = routing.replace("%s", "");
        if (routing.length() - withoutPlaceholders.length() != 4 || withoutPlaceholders.contains("%"))
            throw new IllegalStateException("Interview prompt routing.txt must contain exactly two %s placeholders; only %% is allowed for literal percent signs");
        return new InterviewPromptTemplates(version, templates);
    }

    private static String required(TemplateReader reader, String file) {
        final String content;
        try {
            content = reader.read(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read interview prompt file: " + file, ex);
        }
        if (content.isBlank()) throw new IllegalStateException("Empty interview prompt file: " + file);
        // 正文首尾换行参与拼接和指纹；仅版本号去除边界空白。
        return content;
    }

    @FunctionalInterface
    private interface TemplateReader {
        String read(String file) throws IOException;
    }
}
