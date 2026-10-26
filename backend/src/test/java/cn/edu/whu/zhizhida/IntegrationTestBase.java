package cn.edu.whu.zhizhida;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = "spring.config.location=classpath:/application-it.yml")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    protected MvcResult performPost(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                        .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    protected MvcResult performPost(String url, Object body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                        .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    protected MvcResult performGet(String url) throws Exception {
        return mockMvc.perform(get(url)
                        .characterEncoding(StandardCharsets.UTF_8.name()))
                .andReturn();
    }

    protected MvcResult performGet(String url, String token) throws Exception {
        return mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + token)
                        .characterEncoding(StandardCharsets.UTF_8.name()))
                .andReturn();
    }

    protected MvcResult performPut(String url, Object body, String token) throws Exception {
        return mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                        .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    protected MvcResult performDelete(String url, String token) throws Exception {
        return mockMvc.perform(delete(url)
                        .header("Authorization", "Bearer " + token)
                        .characterEncoding(StandardCharsets.UTF_8.name()))
                .andReturn();
    }

    protected MvcResult performDeleteWithBody(String url, Object body, String token) throws Exception {
        return mockMvc.perform(delete(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                        .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    protected Map<String, Object> parseJsonResult(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(content,
                new TypeReference<Map<String, Object>>() {});
    }

    protected <T> T parseJsonResult(MvcResult result, Class<T> clazz) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(content, clazz);
    }

    protected String extractTokenFromAuthResponse(MvcResult result) throws Exception {
        Map<String, Object> response = parseJsonResult(result);
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (String) data.get("token");
    }

    protected MvcResult registerUser(String username, String password, String displayName) throws Exception {
        return performPost("/api/auth/register", Map.of(
                "username", username,
                "password", password,
                "displayName", displayName
        ));
    }

    protected MvcResult loginUser(String username, String password) throws Exception {
        return performPost("/api/auth/login", Map.of(
                "username", username,
                "password", password
        ));
    }

    protected String registerAndLogin(String username, String password, String displayName) throws Exception {
        registerUser(username, password, displayName);
        MvcResult loginResult = loginUser(username, password);
        return extractTokenFromAuthResponse(loginResult);
    }

    protected String registerAndLoginWithUniqueUsername(String password, String displayName) throws Exception {
        String username;
        MvcResult registerResult;
        int attempts = 0;
        do {
            username = "u" + System.currentTimeMillis() + (int)(Math.random() * 10000);
            registerResult = registerUser(username, password, displayName);
            attempts++;
        } while (registerResult.getResponse().getStatus() == 409 && attempts < 5);
        
        if (registerResult.getResponse().getStatus() != 200) {
            throw new RuntimeException("注册失败: " + registerResult.getResponse().getContentAsString());
        }
        
        MvcResult loginResult = loginUser(username, password);
        return extractTokenFromAuthResponse(loginResult);
    }

    protected boolean listContainsLong(List<?> list, Long value) {
        for (Object item : list) {
            if (item instanceof Number) {
                if (((Number) item).longValue() == value) {
                    return true;
                }
            }
        }
        return false;
    }
}
