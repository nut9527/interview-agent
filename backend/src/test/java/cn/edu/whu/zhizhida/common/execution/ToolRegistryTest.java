package cn.edu.whu.zhizhida.common.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ToolRegistryTest {
        private final JsonMapper json = new JsonMapper();
        private final AtomicInteger calls = new AtomicInteger();

        private ToolRegistry.Registration<String, String> tool(
                        String name, String version, Set<String> scopes) {
                var definition = json.readTree(
                                """
                                                {"type":"function","function":{"name":"%s","description":"fixture",
                                                 "parameters":{"type":"object","properties":{"query":{"type":"string"}},
                                                 "required":["query"],"additionalProperties":false}}}
                                                """
                                                .formatted(name));
                return new ToolRegistry.Registration<>(
                                definition,
                                version,
                                scopes,
                                (context, args) -> {
                                        if (args == null || args.size() != 1 || !args.path("query").isString())
                                                throw new RunFailureException("TOOL_ARGUMENTS");
                                },
                                (context, args) -> {
                                        calls.incrementAndGet();
                                        ((ObjectNode) args).put("query", "callback-mutated");
                                        return context;
                                });
        }

        @Test
        void duplicatesAndMalformedDefinitionsFailAtRegistration() {
                var first = tool("reference", "v1", Set.of());
                assertThatThrownBy(() -> new ToolRegistry<>(List.of(first, first)))
                                .hasMessageContaining("Duplicate");
                assertThatThrownBy(() -> tool("has space", "v1", Set.of()))
                                .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> tool("reference", "", Set.of()))
                                .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(
                                () -> new ToolRegistry.Registration<>(
                                                json.createObjectNode(),
                                                "v1",
                                                Set.of(),
                                                (context, args) -> {
                                                },
                                                (context, args) -> ""))
                                .isInstanceOf(IllegalArgumentException.class);
                var malformed = first.definition();
                ((ObjectNode) malformed.path("function")).put("name", 123);
                assertThatThrownBy(
                                () -> new ToolRegistry.Registration<>(
                                                malformed,
                                                "v1",
                                                Set.of(),
                                                (context, args) -> {
                                                },
                                                (context, args) -> ""))
                                .isInstanceOf(IllegalArgumentException.class);
                ((ObjectNode) malformed.path("function")).put("name", "reference").put("description", " ");
                assertThatThrownBy(
                                () -> new ToolRegistry.Registration<>(
                                                malformed,
                                                "v1",
                                                Set.of(),
                                                (context, args) -> {
                                                },
                                                (context, args) -> ""))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void hiddenToolsCannotBeCalledByNameOrForgedArguments() {
                var registry = new ToolRegistry<>(List.of(tool("history", "v1", Set.of("memory"))));
                assertThat(registry.definitions(Set.of())).isEmpty();
                assertThat(registry.definitions(Set.of("memory"))).hasSize(1);
                assertThatThrownBy(
                                () -> registry.execute(
                                                "history",
                                                json.readTree("{\"query\":\"x\"}"),
                                                Set.of(),
                                                "owner"))
                                .hasMessage("TOOL_DENIED");
                assertThatThrownBy(
                                () -> registry.execute(
                                                "history",
                                                json.readTree("{\"query\":\"x\",\"ownerId\":2}"),
                                                Set.of("memory"),
                                                "owner"))
                                .hasMessage("TOOL_ARGUMENTS");
                assertThat(calls).hasValue(0);
        }

        @Test
        void executeRevalidatesAndUsesOnlyInjectedContext() {
                var registry = new ToolRegistry<>(List.of(tool("reference", "v1", Set.of())));
                var args = (ObjectNode) json.readTree("{\"query\":\"x\"}");
                registry.validate("reference", args, Set.of(), "owner-one");
                args.put("ownerId", 2);
                assertThatThrownBy(() -> registry.execute("reference", args, Set.of(), "owner-one"))
                                .hasMessage("TOOL_ARGUMENTS");
                args.remove("ownerId");
                assertThat(registry.execute("reference", args, Set.of(), "owner-one"))
                                .isEqualTo("owner-one");
                assertThat(registry.execute("reference", args, Set.of(), "owner-two"))
                                .isEqualTo("owner-two");
                assertThat(args.path("query").asString()).isEqualTo("x");
                assertThat(calls).hasValue(2);
        }

        @Test
        void nullUnknownOrPreviouslyAuthorizedToolNeverBypassesScopeChecks() {
                var registry = new ToolRegistry<>(List.of(tool("history", "v1", Set.of("memory"))));
                JsonNode args = json.readTree("{\"query\":\"x\"}");
                registry.validate("history", args, Set.of("memory"), "owner");
                assertThatThrownBy(() -> registry.execute("history", args, Set.of(), "owner"))
                                .hasMessage("TOOL_DENIED");
                assertThatThrownBy(() -> registry.execute(null, args, Set.of(), "owner"))
                                .hasMessage("TOOL_DENIED");
                assertThatThrownBy(() -> registry.validate("shell", args, Set.of(), "owner"))
                                .hasMessage("TOOL_DENIED");
                assertThat(calls).hasValue(0);
        }

        @Test
        void callersCannotMutateRegisteredSchemaOrManifest() {
                var tool = tool("reference", "v1", Set.of());
                var registry = new ToolRegistry<>(List.of(tool));
                String before = json.writeValueAsString(registry.manifest());
                ((ObjectNode) tool.definition().path("function")).put("name", "shell");
                ((ObjectNode) registry.allDefinitions().getFirst().path("function")).put("name", "shell");
                ((ObjectNode) ((JsonNode) registry.manifest().getFirst().get("definition"))
                                .path("function"))
                                .put("name", "shell");
                assertThat(json.writeValueAsString(registry.manifest())).isEqualTo(before);
                assertThatThrownBy(() -> registry.execute("shell", null, Set.of(), "owner"))
                                .hasMessage("TOOL_DENIED");
        }

        @Test
        void manifestBindsVersionAndScopesAndIsIndependentOfProviderDiscoveryOrder() {
                var a = tool("a", "v1", Set.of("b", "a"));
                var b = tool("b", "v1", Set.of());
                var first = new ToolRegistry<>(List.of(a, b));
                assertThat(json.writeValueAsString(first.manifest()))
                                .isEqualTo(json.writeValueAsString(new ToolRegistry<>(List.of(b, a)).manifest()));
                assertThat(first.manifest())
                                .isNotEqualTo(
                                                new ToolRegistry<>(List.of(tool("a", "v2", Set.of("a", "b")), b))
                                                                .manifest());
                assertThat(first.manifest())
                                .isNotEqualTo(new ToolRegistry<>(List.of(tool("a", "v1", Set.of()), b)).manifest());
                assertThat(first.requiresScope("a", "a")).isTrue();
                assertThat(first.requiresScope("b", "a")).isFalse();
        }
}
