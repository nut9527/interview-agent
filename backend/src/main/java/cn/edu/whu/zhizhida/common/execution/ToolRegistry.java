package cn.edu.whu.zhizhida.common.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import tools.jackson.databind.JsonNode;

/** 仅分发一次只读调用，准入、批次、回执和恢复由调用方负责。 */
public final class ToolRegistry<C, R> {
    /** 限制序列化后 UTF-16 正文大小，不代表 token 或货币预算。 */
    public record Limits(long timeoutMillis, int maxResultChars) {
        public static final Limits LOCAL = new Limits(5_000, 24_000);
        public Limits {
            if (timeoutMillis < 1 || timeoutMillis > 30_000 || maxResultChars < 1 || maxResultChars > 64_000)
                throw new IllegalArgumentException("Invalid tool limits");
        }
    }
    public record Registration<C, R>(JsonNode definition, String version, Set<String> requiredScopes,
            Limits limits, BiConsumer<C, JsonNode> validate, BiFunction<C, JsonNode, R> execute) {
        public Registration(JsonNode definition, String version, Set<String> requiredScopes,
                BiConsumer<C, JsonNode> validate, BiFunction<C, JsonNode, R> execute) {
            this(definition, version, requiredScopes, Limits.LOCAL, validate, execute);
        }
        public Registration {
            Objects.requireNonNull(limits);
            Objects.requireNonNull(definition);
            if (!"function".equals(definition.path("type").asText())
                    || !definition.path("function").path("name").isTextual()
                    || !definition.path("function").path("name").asText().matches("[a-zA-Z0-9_-]{1,64}")
                    || !definition.path("function").path("description").isTextual()
                    || definition.path("function").path("description").asText().isBlank()
                    || !definition.path("function").path("parameters").isObject()
                    || version == null || version.isBlank()) {
                throw new IllegalArgumentException("Invalid tool registration");
            }
            definition = definition.deepCopy();
            requiredScopes = Set.copyOf(requiredScopes);
            Objects.requireNonNull(validate);
            Objects.requireNonNull(execute);
        }
        @Override public JsonNode definition() { return definition.deepCopy(); }
        public String name() { return definition.path("function").path("name").asText(); }
    }

    private final Map<String, Registration<C, R>> tools;

    public ToolRegistry(List<Registration<C, R>> registrations) {
        var indexed = new LinkedHashMap<String, Registration<C, R>>();
        for (var tool : registrations) {
            if (indexed.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalArgumentException("Duplicate tool registration: " + tool.name());
        }
        tools = java.util.Collections.unmodifiableMap(indexed);
    }

    public List<JsonNode> definitions(Set<String> scopes) {
        return tools.values().stream().filter(tool -> scopes.containsAll(tool.requiredScopes()))
                .map(Registration::definition).toList();
    }

    public List<JsonNode> allDefinitions() { return tools.values().stream().map(Registration::definition).toList(); }

    /** 私有配置指纹输入须稳定，不包含用户上下文或模型参数。 */
    public List<Map<String, Object>> manifest() {
        return tools.values().stream().sorted(java.util.Comparator.comparing(Registration::name))
                .map(tool -> (Map<String, Object>) new java.util.TreeMap<>(Map.<String, Object>of(
                        "definition", tool.definition(), "version", tool.version(), "effect", "READ_ONLY",
                        "executionPolicy", "bounded-tool-v1", "timeoutMillis", tool.limits().timeoutMillis(),
                        "maxResultChars", tool.limits().maxResultChars(), "resultMeasurement", "SERIALIZED_UTF16",
                        "requiredScopes", tool.requiredScopes().stream().sorted().toList())))
                .toList();
    }

    public boolean requiresScope(String name, String scope) { return require(name).requiredScopes().contains(scope); }

    public Limits limits(String name) { return require(name).limits(); }

    public void validate(String name, JsonNode args, Set<String> scopes, C context) {
        var tool = authorized(name, scopes);
        tool.validate().accept(context, args);
    }

    /** 每次执行均复核 scope 与业务参数；超时及结果大小由调用方按 limits 另行约束。 */
    public R execute(String name, JsonNode args, Set<String> scopes, C context) {
        var tool = authorized(name, scopes);
        // 不依赖上批校验，也不向回调暴露调用方可变的参数树。
        JsonNode input = args == null ? null : args.deepCopy();
        tool.validate().accept(context, input);
        return tool.execute().apply(context, input);
    }

    private Registration<C, R> authorized(String name, Set<String> scopes) {
        var tool = require(name);
        if (!scopes.containsAll(tool.requiredScopes())) throw new RunFailureException("TOOL_DENIED");
        return tool;
    }

    private Registration<C, R> require(String name) {
        var tool = tools.get(name);
        if (tool == null) throw new RunFailureException("TOOL_DENIED");
        return tool;
    }
}
