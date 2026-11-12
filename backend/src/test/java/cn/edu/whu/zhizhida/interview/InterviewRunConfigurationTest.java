package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.common.JsonFingerprint;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.interview.execution.InterviewEvidenceTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewRunConfigurationTest {
    @Test void fingerprintsAreStableAcrossProcessesAndObjectOrderButBindArrayOrder() throws Exception {
        var json = new JsonMapper();
        var first = json.readTree("{\"b\":{\"z\":2,\"a\":1},\"a\":[1,2]}");
        var reordered = json.readTree("{\"a\":[1,2],\"b\":{\"a\":1,\"z\":2}}");
        assertThat(JsonFingerprint.sha(first)).isEqualTo(JsonFingerprint.sha(reordered));
        assertThat(JsonFingerprint.sha(first)).isNotEqualTo(JsonFingerprint.sha(
                json.readTree("{\"a\":[2,1],\"b\":{\"a\":1,\"z\":2}}")));
        String expected = JsonFingerprint.sha(new InterviewPromptBuilder(json).templateManifest());
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (int i = 0; i < 4; i++) {
            var process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), FingerprintProbe.class.getName())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
                assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim()).isEqualTo(expected);
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    public static class FingerprintProbe {
        public static void main(String[] args) {
            System.out.println(JsonFingerprint.sha(new InterviewPromptBuilder(new JsonMapper()).templateManifest()));
        }
    }

    @Test void localDecisionsKeepThePhaseEnvelopeWithoutContactingTheProvider() {
        var json = new JsonMapper();
        var client = mock(ToolCallingChatClient.class);
        var source = new InterviewAiConfiguration().interviewDecisionSource(AiMode.MOCK, json);
        var model = new InterviewRunModel(client, json, source, AiMode.MOCK, mock(InterviewEvidenceTools.class), InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        var session = mock(cn.edu.whu.zhizhida.interview.api.InterviewSession.class);
        when(session.targetRole()).thenReturn("Java");
        String configuration = model.configuration();
        var action = model.call(configuration, List.of(), List.of(), false, 1000, "first", session, null, "", "ACTION");
        assertThat(InterviewDecisionContract.parse(json, action.message().path("content").asText(), "ACTION")
                .path("action").asText()).isEqualTo("COMPLETE");
        var result = model.call(configuration, List.of(), List.of(), false, 1000, "second", session, null, "", "FINAL");
        assertThat(InterviewDecisionContract.parse(json, result.message().path("content").asText(), "FINAL")
                .path("question").asText()).isNotBlank();
        verify(client, never()).complete(any());
    }

    @Test void contextSelectionPolicyCannotChangeUnderAnAcceptedRun() {
        var json = new JsonMapper();
        var model = new InterviewRunModel(mock(ToolCallingChatClient.class), json, null,
                AiMode.MODEL, mock(InterviewEvidenceTools.class), InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        var config = (tools.jackson.databind.node.ObjectNode) json.readTree(model.configuration());
        assertThat(config.at("/contextPolicy/measurement").asText()).isEqualTo("SERIALIZED_UTF16_NOT_TOKENS");
        config.withObject("contextPolicy").put("maxToolChars", 999999);
        assertThatThrownBy(() -> model.requireConfiguration(config.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        config.remove("contextPolicy");
        assertThatThrownBy(() -> model.requireConfiguration(config.toString())).hasMessage("MODEL_CONFIG_CHANGED");
    }
    @Test void retiredDraftProjectionAndPreviousPolicyCannotResumeWithTheCurrentModel() {
        var json = new JsonMapper();
        var client = mock(ToolCallingChatClient.class);
        var model = new InterviewRunModel(client, json, null, AiMode.MODEL, mock(InterviewEvidenceTools.class), InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        var current = (tools.jackson.databind.node.ObjectNode) json.readTree(model.configuration());
        assertThat(current.has("streamProjection")).isFalse();
        assertThat(current.path("policy").asText()).isEqualTo("interview-agent-v3-v6");
        model.requireConfiguration(current.toString());

        var streaming = current.deepCopy().put("streamProjection", "question-draft-v1");
        assertThatThrownBy(() -> model.call(streaming.toString(), List.of(), List.of(), false,
                1000, "retired-stream", null, null, "", "FINAL")).hasMessage("MODEL_CONFIG_CHANGED");
        var previous = current.deepCopy().put("policy", "interview-agent-v3-v4-v5");
        assertThatThrownBy(() -> model.requireConfiguration(previous.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        verify(client, never()).complete(any());
    }
    @Test void budgetDefaultsDoNotRewriteSavedRunsAndCandidatesFailAtStartup() {
        var json = new JsonMapper();
        String saved = configuredModel("B0", "phased-v2").configuration();
        var model = configuredModel("S1", "phased-v2");
        model.validateProtocol();
        model.requireConfiguration(saved);
        var old = (tools.jackson.databind.node.ObjectNode) json.readTree(saved);
        old.remove("budgetProfile");
        model.requireConfiguration(old.toString());
        var changed = (tools.jackson.databind.node.ObjectNode) json.readTree(saved);
        changed.withObject("budgetProfile").put("version", "budget-future");
        assertThatThrownBy(() -> model.requireConfiguration(changed.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        var unsupported = configuredModel("INTERACTIVE", "phased-v2");
        assertThatThrownBy(unsupported::validateProtocol).hasMessageContaining("not enabled");
        assertThatThrownBy(unsupported::configuration).hasMessageContaining("not enabled");
    }

    @Test void onlyPhasedProtocolIsAcceptedAndSavedRunsAreFenced() {
        var json = new JsonMapper();
        var model = configuredModel("A1", "phased-v2");
        model.validateProtocol();
        var saved = (tools.jackson.databind.node.ObjectNode) json.readTree(model.configuration());
        model.requireConfiguration(saved.toString());
        saved.remove("decisionProtocol");
        assertThatThrownBy(() -> model.requireConfiguration(saved.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        assertThatThrownBy(configuredModel("A1", "legacy")::validateProtocol).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(configuredModel("A1", "typo")::validateProtocol).isInstanceOf(IllegalArgumentException.class);
    }
    private InterviewRunModel configuredModel(String loopPolicy, String decisionProtocol) {
        return new InterviewRunModel(mock(ToolCallingChatClient.class), new JsonMapper(), null,
                AiMode.MODEL, mock(InterviewEvidenceTools.class), InterviewPromptTemplates.defaults(),
                loopPolicy, decisionProtocol);
    }
    @Test void fingerprintsBindTemplatesToolsAndEffectiveTransportWithoutUserInput() {
        var json = new JsonMapper();
        var client = mock(ToolCallingChatClient.class);
        when(client.configuration()).thenReturn(new ToolCallingChatClient.Config("fixture", "disabled", "v1"));
        when(client.executionProfile()).thenReturn(Map.of("endpointSha256", "first", "maxOutputTokens", "2000"));
        var tools = mock(InterviewEvidenceTools.class);
        when(tools.allDefinitions()).thenReturn(List.of(json.createObjectNode().put("description", "first")));
        var model = new InterviewRunModel(client, json, null, AiMode.MODEL, tools, InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        var first = json.readTree(model.configuration());
        assertThat(first.path("fingerprintFormat").asText()).isEqualTo("sorted-json-v1");
        var previousRetrieval = (tools.jackson.databind.node.ObjectNode) first.deepCopy();
        previousRetrieval.remove("initialKnowledgeRetrieval");
        assertThatThrownBy(() -> model.requireConfiguration(previousRetrieval.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        var previous = (tools.jackson.databind.node.ObjectNode) first.deepCopy();
        previous.remove("fingerprintFormat");
        assertThatThrownBy(() -> model.requireConfiguration(previous.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        assertThat(first.path("templateSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(first.path("toolDefinitionsSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(json.readTree(model.configuration())).isEqualTo(first);
        when(tools.allDefinitions()).thenReturn(List.of(json.createObjectNode().put("description", "changed")));
        assertThat(json.readTree(model.configuration())).isEqualTo(first);
        verify(tools, times(1)).allDefinitions();
        verify(tools, times(1)).registryManifest();
        var restarted = new InterviewRunModel(client, json, null, AiMode.MODEL, tools,
                InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        var changed = json.readTree(restarted.configuration());
        assertThat(changed.path("templateSha256")).isEqualTo(first.path("templateSha256"));
        assertThat(changed.path("toolDefinitionsSha256")).isNotEqualTo(first.path("toolDefinitionsSha256"));
        assertThatThrownBy(() -> restarted.requireConfiguration(first.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        when(client.executionProfile()).thenReturn(Map.of("endpointSha256", "changed", "maxOutputTokens", "2000"));
        assertThat(json.readTree(model.configuration()).path("executionProfile")).isNotEqualTo(first.path("executionProfile"));
        verify(client, never()).complete(any());
    }

    @Test void implementationOrPermissionChangesFenceSavedRunsEvenWhenModelSchemaIsUnchanged() {
        var json = new JsonMapper();
        var client = mock(ToolCallingChatClient.class);
        var tools = mock(InterviewEvidenceTools.class);
        when(tools.allDefinitions()).thenReturn(List.of());
        when(tools.registryManifest()).thenReturn(List.of(Map.of("version", "v1", "requiredScopes", List.of())));
        var model = new InterviewRunModel(client, json, null, AiMode.MODEL, tools, InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        String original = model.configuration();
        when(tools.registryManifest()).thenReturn(List.of(Map.of("version", "v2", "requiredScopes", List.of())));
        var restarted = new InterviewRunModel(client, json, null, AiMode.MODEL, tools,
                InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        assertThatThrownBy(() -> restarted.requireConfiguration(original)).hasMessage("MODEL_CONFIG_CHANGED");
        when(tools.registryManifest()).thenReturn(List.of(Map.of("version", "v1", "requiredScopes", List.of("memory"))));
        var permissionsChanged = new InterviewRunModel(client, json, null, AiMode.MODEL, tools,
                InterviewPromptTemplates.defaults(), "A1", "phased-v2");
        assertThatThrownBy(() -> permissionsChanged.requireConfiguration(original)).hasMessage("MODEL_CONFIG_CHANGED");
        var old = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        old.remove("toolRegistrySha256");
        assertThatThrownBy(() -> model.requireConfiguration(old.toString())).hasMessage("MODEL_CONFIG_CHANGED");
        verify(client, never()).complete(any());
    }
}
