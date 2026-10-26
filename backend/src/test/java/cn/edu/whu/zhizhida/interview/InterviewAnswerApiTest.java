package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import cn.edu.whu.zhizhida.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InterviewAnswerApiTest {
    private final InterviewService service = mock(InterviewService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InterviewController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void acceptsLegacyAndIdempotentRequests() throws Exception {
        mvc.perform(post("/api/interviews/sessions/session/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"我的回答\"}")).andExpect(status().isOk());
        verify(service).answer("session", new InterviewAnswerRequest("我的回答"));
        mvc.perform(post("/api/interviews/sessions/session/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"我的回答\",\"requestId\":\"answer_001\",\"expectedTurn\":1}"))
                .andExpect(status().isOk());
        verify(service).answer("session", new InterviewAnswerRequest("我的回答", "answer_001", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"answer\":\"回答\",\"requestId\":\"short\"}",
            "{\"answer\":\"回答\",\"requestId\":\"invalid request id\"}",
            "{\"answer\":\"回答\",\"expectedTurn\":0}",
            "{\"answer\":\"回答\",\"expectedTurn\":13}",
            "{\"answer\":\"  \"}"
    })
    void rejectsInvalidMetadataBeforeCallingService(String json) throws Exception {
        mvc.perform(post("/api/interviews/sessions/session/answers").contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"targetRole\":\"Java 后端\",\"totalRounds\":0}",
            "{\"targetRole\":\"Java 后端\",\"totalRounds\":13}",
            "{\"targetRole\":\"Java 后端\",\"mode\":\"UNSUPPORTED\"}"
    })
    void rejectsUnsupportedInterviewConfiguration(String json) throws Exception {
        mvc.perform(post("/api/interviews/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void conflictPreservesHttpSemantics() throws Exception {
        when(service.answer(eq("session"), any())).thenThrow(new BusinessException(HttpStatus.CONFLICT, "题目已更新"));
        mvc.perform(post("/api/interviews/sessions/session/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"回答\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void oldPersistedTurnsRemainReadableWithoutRequestId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InterviewTurn old = mapper.readValue("{\"speaker\":\"user\",\"content\":\"旧回答\"}", InterviewTurn.class);
        assertThat(old.requestId()).isNull();
        assertThat(mapper.writeValueAsString(old)).doesNotContain("requestId");
        InterviewTurn receipt = new InterviewTurn("user", "新回答", "answer_001");
        assertThat(mapper.readValue(mapper.writeValueAsString(receipt), InterviewTurn.class)).isEqualTo(receipt);
    }
}
