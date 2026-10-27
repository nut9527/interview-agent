package cn.edu.whu.zhizhida.common;

import cn.edu.whu.zhizhida.auth.BearerTokenFilter;
import cn.edu.whu.zhizhida.auth.SecurityConfig;
import cn.edu.whu.zhizhida.auth.api.AuthService;
import cn.edu.whu.zhizhida.resume.ResumeController;
import cn.edu.whu.zhizhida.resume.api.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(properties = "spring.config.location=classpath:/application-contract.yml",
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {ResumeController.class, SecurityConfig.class, BearerTokenFilter.class, ApiExceptionHandler.class})
class SecurityErrorContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean ResumeService resumes;

    @Test
    void unauthenticatedFilterResponseUsesTheSameErrorEnvelopeWithoutCallingBusinessServices() throws Exception {
        mvc.perform(get("/api/resumes")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").value("请先登录"));
        verifyNoInteractions(auth, resumes);
    }
}
