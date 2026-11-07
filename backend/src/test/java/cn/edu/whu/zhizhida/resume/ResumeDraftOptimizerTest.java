package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeRequest.Entry;
import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeRequest.Kind;
import cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeResponse.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumeDraftOptimizerTest {
    private final JsonMapper json = new JsonMapper();
    private final JsonChatClient client = mock(JsonChatClient.class);
    private final ResumeDraftOptimizer model = new ResumeDraftOptimizer(AiMode.MODEL, client, json, BusinessMetrics.disabled());
    private final ResumeDraftOptimizeRequest request = new ResumeDraftOptimizeRequest("后端开发", List.of(
            new Entry(Kind.SKILL, "Java", null),
            new Entry(Kind.PROJECT_CONTRIBUTION, "我主要负责写了 3 条接口用例", "校园借阅")));

    @Test
    void sendsDraftOnceAndMapsSuggestionsBackToIndexedEntries() {
        reply("""
                {"changes":[{"index":1,"text":"编写 3 条接口用例。"},{"index":0,"text":"Java"}]}
                """);
        assertThat(model.optimize(request).changes()).containsExactly(new Change(1, "编写 3 条接口用例。"));
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeJsonDirectly(anyString(), prompt.capture(), eq(0.2), eq(8192));
        var input = json.readTree(prompt.getValue());
        assertThat(input.path("targetRole").asText()).isEqualTo("后端开发");
        assertThat(input.at("/entries/1/kind").asText()).isEqualTo("projectContribution");
        assertThat(input.at("/entries/1/index").intValue()).isEqualTo(1);
        assertThat(input.at("/entries/1/projectName").asText()).isEqualTo("校园借阅");
        verifyNoMoreInteractions(client);
    }

    @Test
    void offlineModeFormatsOnlyExistingTextWithoutCallingModel() {
        var offline = new ResumeDraftOptimizer(AiMode.MOCK, client, json, BusinessMetrics.disabled());
        assertThat(offline.optimize(request).changes()).containsExactly(new Change(1, "我主要负责写了 3 条接口用例。"));
        verifyNoInteractions(client);
    }

    @Test
    void draftEndpointAcceptsUnsavedResumeWithoutTargetAndValidatesIndividualEntries() throws Exception {
        var offline = new ResumeDraftOptimizer(AiMode.MOCK, client, json, BusinessMetrics.disabled());
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(offline))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String route = "/api/ai/resume/optimize-draft";
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"kind\":\"summary\",\"text\":\"负责接口开发\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.changes[0].index").value(0))
                .andExpect(jsonPath("$.data.changes[0].text").value("负责接口开发。"));
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"kind\":\"projectExperience\",\"text\":\"  \"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"kind\":\"email\",\"text\":\"forbidden\"}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void acceptsAnExplicitNoChangeResponse() {
        reply("{\"changes\":[]}");
        assertThat(model.optimize(request).changes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"changes\":null}", "{\"changes\":[{\"index\":\"0\",\"text\":\"x\"}]}",
            "{\"changes\":[{\"index\":-1,\"text\":\"x\"}]}", "{\"changes\":[{\"index\":2,\"text\":\"x\"}]}",
            "{\"changes\":[{\"index\":0,\"text\":\"x\"},{\"index\":0,\"text\":\"y\"}]}",
            "{\"changes\":[{\"index\":1,\"text\":\"编写 99 个接口\"}]}",
            "{\"changes\":[{\"index\":1,\"text\":\"拆成\\n两条\"}]}"})
    void rejectsUnusableOrMisassignedSuggestions(String output) {
        reply(output);
        assertThatThrownBy(() -> model.optimize(request)).isInstanceOf(StructuredOutput.FieldValidationException.class);
    }

    @Test
    void respectsFieldAndAggregateInputBudgetsBeforeCallingProvider() {
        var longSkill = new ResumeDraftOptimizeRequest(null, List.of(new Entry(Kind.SKILL, "x".repeat(81), null)));
        assertThatThrownBy(() -> model.optimize(longSkill)).isInstanceOf(UserInputException.class);
        var longDraft = new ResumeDraftOptimizeRequest("后端", Collections.nCopies(4,
                new Entry(Kind.SUMMARY, "x".repeat(4000), null)));
        assertThatThrownBy(() -> model.optimize(longDraft)).isInstanceOf(UserInputException.class);
        verifyNoInteractions(client);
    }

    private void reply(String result) {
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.2), eq(8192))).thenReturn(json.readTree(result));
    }
}
