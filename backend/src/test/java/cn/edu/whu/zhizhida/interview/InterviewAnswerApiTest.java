package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunEvents;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunService;
import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import cn.edu.whu.zhizhida.common.BusinessException;
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
    private final InterviewRunService runs = mock(InterviewRunService.class);
    private final InterviewService sessions = mock(InterviewService.class);
    private final TrainingTaskRunService tasks = mock(TrainingTaskRunService.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InterviewController(sessions),
                new InterviewRunController(runs, mock(InterviewRunEvents.class), tasks))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void acceptsCommandWithoutPollingForCompletion() throws Exception {
        when(runs.answer(anyString(), any())).thenReturn(new InterviewRun("run-1", "ACCEPTED", "QUEUED", "session",
                1, null, 1, null, "/api/interview-runs/run-1", "/api/interview-runs/run-1/events"));
        mvc.perform(post("/api/interviews/sessions/session/answer-commands").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"我的回答\",\"requestId\":\"answer_001\",\"expectedTurn\":1}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        verify(runs).answer("session", new InterviewRun.Answer("answer_001", 1, "我的回答"));
        verifyNoMoreInteractions(runs);
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"answer\":\"回答\",\"requestId\":\"short\",\"expectedTurn\":1}",
            "{\"answer\":\"回答\",\"requestId\":\"invalid request id\",\"expectedTurn\":1}",
            "{\"answer\":\"回答\",\"requestId\":\"answer_001\",\"expectedTurn\":0}",
            "{\"answer\":\"回答\",\"requestId\":\"answer_001\",\"expectedTurn\":13}",
            "{\"answer\":\"  \" ,\"requestId\":\"answer_001\",\"expectedTurn\":1}",
            "{\"answer\":\"回答\"}"
    })
    void requiresIdempotencyAndTurnMetadata(String json) throws Exception {
        mvc.perform(post("/api/interviews/sessions/session/answer-commands").contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"targetRole\":\"Java 后端\",\"totalRounds\":0}",
            "{\"targetRole\":\"Java 后端\",\"totalRounds\":13}",
            "{\"targetRole\":\"Java 后端\",\"mode\":\"UNSUPPORTED\"}",
            "{\"targetRole\":\"Java 后端\",\"timeLimitMinutes\":0}",
            "{\"targetRole\":\"Java 后端\",\"timeLimitMinutes\":181}",
            "{\"targetRole\":\"Java 后端\",\"experienceMode\":\"OTHER\"}"
    })
    void rejectsUnsupportedInterviewConfiguration(String json) throws Exception {
        mvc.perform(post("/api/interviews/start-commands").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"start_001\",\"configuration\":" + json + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }

    @Test void conflictPreservesHttpSemantics() throws Exception {
        when(runs.answer(eq("session"), any())).thenThrow(new BusinessException(HttpStatus.CONFLICT, "题目已更新"));
        mvc.perform(post("/api/interviews/sessions/session/answer-commands").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"回答\",\"requestId\":\"answer_001\",\"expectedTurn\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void removedSynchronousRoutesCannotExecuteCommands() throws Exception {
        mvc.perform(post("/api/interviews/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/interviews/sessions/session/answers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(runs, sessions);
    }

    @Test void oldPersistedTurnsRemainReadableWithoutRequestId() throws Exception {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        InterviewTurn old = mapper.readValue("{\"speaker\":\"user\",\"content\":\"旧回答\"}", InterviewTurn.class);
        assertThat(old.requestId()).isNull();
        assertThat(mapper.writeValueAsString(old)).doesNotContain("requestId");
        InterviewTurn receipt = new InterviewTurn("user", "新回答", "answer_001");
        assertThat(mapper.readValue(mapper.writeValueAsString(receipt), InterviewTurn.class)).isEqualTo(receipt);
    }
}
