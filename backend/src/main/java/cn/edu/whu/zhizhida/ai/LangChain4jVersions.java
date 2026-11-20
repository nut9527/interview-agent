package cn.edu.whu.zhizhida.ai;

import java.io.IOException;
import java.util.Properties;

/** 冻结实际加载的 SDK 版本，恢复旧任务时仍使用原有的指纹格式。 */
final class LangChain4jVersions {
    static final String OPEN_AI = artifactVersion("langchain4j-open-ai");
    static final String MODEL_SDK = "langchain4j-" + OPEN_AI + "-jackson3-"
            + artifactVersion("langchain4j-core-jackson3");

    private LangChain4jVersions() {
    }

    private static String artifactVersion(String artifact) {
        String path = "/META-INF/maven/dev.langchain4j/" + artifact + "/pom.properties";
        try (var stream = LangChain4jVersions.class.getResourceAsStream(path)) {
            if (stream == null)
                throw new IllegalStateException("Missing SDK metadata: " + path);
            var properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Missing SDK version: " + path);
            }
            return version;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read SDK metadata: " + path, ex);
        }
    }
}
