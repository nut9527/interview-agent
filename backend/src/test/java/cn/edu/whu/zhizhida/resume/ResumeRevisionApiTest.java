package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeService;

import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import cn.edu.whu.zhizhida.common.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumeRevisionApiTest {
    private final ResumeService service = mock(ResumeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ResumeController(service))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }

    @Test
    void parseReturnsEditableRequestWithoutCallingLegacyImportOrCreate() throws Exception {
        var parsed = new cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest("待核对", "Java", "原文",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "classic");
        when(service.parseDocument(any(), eq("Java"))).thenReturn(parsed);
        mvc.perform(multipart("/api/resumes/import/parse")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1}))
                        .param("targetRole", "Java"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerName").value("待核对"))
                .andExpect(jsonPath("$.data.id").doesNotExist());
        verify(service).parseDocument(any(), eq("Java"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void updatePassesRevisionAndReportsConflict() throws Exception {
        when(service.update(eq(1L), any())).thenThrow(new ResumeConflictException());
        mvc.perform(put("/api/resumes/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"test\",\"targetRole\":\"Java\",\"revision\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESUME_REVISION_CONFLICT"))
                .andExpect(header().exists(RequestIdFilter.HEADER));
        verify(service).update(eq(1L), argThat(request -> request.revision() == 2L));
    }

    @Test
    void restoreSupportsOldClientsAndRevisionAwareClients() throws Exception {
        mvc.perform(post("/api/resumes/1/versions/3/restore")).andExpect(status().isOk());
        verify(service).restore(1L, 3L, null);
        mvc.perform(post("/api/resumes/1/versions/3/restore").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":2}"))
                .andExpect(status().isOk());
        verify(service).restore(1L, 3L, 2L);
    }

    @Test
    void rejectsNegativeRevisionsBeforeBusinessExecution() throws Exception {
        mvc.perform(put("/api/resumes/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"test\",\"targetRole\":\"Java\",\"revision\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/resumes/1/versions/3/restore").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonAndPathAreClientErrors() throws Exception {
        mvc.perform(put("/api/resumes/1").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/resumes/not-a-number")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unexpectedErrorDoesNotExposeInternalDetails() throws Exception {
        when(service.findById(1L)).thenThrow(new RuntimeException("jdbc:password=secret SELECT * FROM users"));
        mvc.perform(get("/api/resumes/1")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))))
                .andExpect(header().exists(RequestIdFilter.HEADER));
    }

    @Test
    void unsupportedMethodReturns405WithAllowedMethods() throws Exception {
        mvc.perform(patch("/api/resumes/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("PUT")))
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mvc.perform(put("/api/resumes/1").contentType(MediaType.TEXT_PLAIN).content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service);
    }
}
