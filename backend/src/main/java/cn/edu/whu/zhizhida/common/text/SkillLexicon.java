package cn.edu.whu.zhizhida.common.text;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 技能别名和词边界共用规则，避免 JavaScript、Django、NoSQL 被当作 Java、Go、SQL。 */
public final class SkillLexicon {
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    private static final Pattern NEGATION = Pattern.compile(
            "(?:不熟悉|不了解|不会|未掌握|未使用(?:过)?|没有(?:使用|接触)(?:过)?|"
                    + "no experience (?:with|in)|not familiar with|without)\\s*$");

    static {
        register("java", "java", "java8", "java11", "java17", "java21");
        register("javascript", "javascript", "js", "ecmascript");
        register("typescript", "typescript", "ts");
        register("spring boot", "spring boot", "springboot", "spring-boot");
        register("spring cloud", "spring cloud", "springcloud", "spring-cloud");
        register("spring", "spring");
        register("vue", "vue", "vue.js", "vuejs", "vue2", "vue3", "vue 3");
        register("react", "react", "react.js", "reactjs");
        register("node.js", "node.js", "nodejs", "node js");
        register("python", "python");
        register("go", "go", "golang");
        register("c++", "c++", "cpp");
        register("c#", "c#", "csharp");
        register("c", "c");
        register(".net", ".net", "dotnet");
        register("mysql", "mysql");
        register("postgresql", "postgresql", "postgres");
        register("sql", "sql");
        register("redis", "redis");
        register("mongodb", "mongodb", "mongo");
        register("docker", "docker");
        register("kubernetes", "kubernetes", "k8s");
        register("mybatis", "mybatis", "mybatis-plus", "mybatis plus");
        register("gitlab ci", "gitlab ci", "gitlab-ci", "gitlabci");
        register("rest", "rest", "restful", "rest api");
        register("llm", "llm", "大模型", "大型语言模型");
        register("rag", "rag", "检索增强生成");
        register("prompt", "prompt", "提示词");
        for (String skill : List.of("html", "css", "linux", "git", "junit", "selenium", "playwright",
                "kafka", "rabbitmq", "pytorch", "tensorflow", "django", "flask", "fastapi", "api", "ai")) {
            register(skill, skill);
        }
    }

    private SkillLexicon() {}

    public static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static String canonical(String skill) {
        String normalized = normalize(skill);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static Set<String> knownSkills() {
        return Set.copyOf(PATTERNS.keySet());
    }

    public static boolean contains(String text, String skill) {
        String canonical = canonical(skill);
        if (canonical.isBlank()) return false;
        Pattern pattern = PATTERNS.get(canonical);
        if (pattern == null) pattern = compile(List.of(canonical));
        String normalized = normalize(text);
        Matcher matcher = pattern.matcher(normalized);
        while (matcher.find()) {
            String prefix = normalized.substring(Math.max(0, matcher.start() - 32), matcher.start());
            if (!NEGATION.matcher(prefix).find()) return true;
        }
        return false;
    }

    private static void register(String canonical, String... aliases) {
        for (String alias : aliases) ALIASES.put(alias, canonical);
        PATTERNS.put(canonical, compile(List.of(aliases)));
    }

    private static Pattern compile(List<String> aliases) {
        String alternatives = aliases.stream().map(alias -> {
            String left = alias.charAt(0) < 128 ? "(?<![a-z0-9_+#.])" : "";
            String right = alias.charAt(alias.length() - 1) < 128 ? "(?![a-z0-9_+#]|\\.[a-z0-9])" : "";
            return left + Pattern.quote(alias).replace(" ", "\\E\\s+\\Q") + right;
        })
                .collect(java.util.stream.Collectors.joining("|"));
        return Pattern.compile("(?:" + alternatives + ")");
    }
}
