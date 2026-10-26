package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TargetJobApiTest {
    TargetJobService service = mock(TargetJobService.class);
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.standaloneSetup(new TargetJobController(service)).setControllerAdvice(new ApiExceptionHandler()).build(); }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"revision\":0,\"title\":\"x\",\"jdText\":\"JD\",\"requirements\":[null]}",
            "{\"revision\":-1,\"title\":\"x\",\"jdText\":\"JD\",\"requirements\":[]}",
            "{\"revision\":0,\"title\":\"x\",\"jdText\":\"JD\",\"requirements\":[{\"id\":\"r\",\"text\":\"x\",\"kind\":\"MUST\",\"keywords\":[null]}]}"})
    void rejectsInvalidPayloadsBeforeService(String body) throws Exception {
        mvc.perform(post("/api/target-jobs").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void rejectsUnboundedJdAndInvalidPagination() throws Exception {
        mvc.perform(post("/api/target-jobs/extract").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdText\":\"" + "a".repeat(20001) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/target-jobs?page=0")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/target-jobs/x")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/target-jobs/x/analysis").contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
