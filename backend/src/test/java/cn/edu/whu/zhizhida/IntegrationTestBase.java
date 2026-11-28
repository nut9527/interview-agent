package cn.edu.whu.zhizhida;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cn.edu.whu.zhizhida.interview.execution.RunContextAssembler;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = "spring.config.location=classpath:/application-it.yml")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
public abstract class IntegrationTestBase {
        protected static RunContextAssembler.Prepared prepared(String requestHash) {
                return new RunContextAssembler.Prepared(
                                java.util.List.of(), java.util.List.of(), false, requestHash, 0, 0, 0);
        }

        @Autowired
        protected MockMvc mockMvc;

        @Autowired
        private cn.edu.whu.zhizhida.interview.execution.InterviewRunStore interviewRuns;

        @Autowired
        private cn.edu.whu.zhizhida.interview.execution.InterviewRunWorker interviewWorker;

        protected final ObjectMapper objectMapper = new tools.jackson.databind.json.JsonMapper();

        /**
         * Test fixture: exercise command acceptance, an explicit worker turn, then the
         * public read API.
         */
        protected MvcResult startInterview(Object configuration, String token) throws Exception {
                return completeInterviewCommand(
                                performPost(
                                                "/api/interviews/start-commands",
                                                Map.of(
                                                                "requestId",
                                                                java.util.UUID.randomUUID().toString(),
                                                                "configuration",
                                                                configuration),
                                                token),
                                token);
        }

        protected MvcResult answerInterview(String sessionId, Map<String, ?> input, String token)
                        throws Exception {
                var command = new java.util.HashMap<String, Object>(input);
                command.putIfAbsent("requestId", java.util.UUID.randomUUID().toString());
                if (!command.containsKey("expectedTurn")) {
                        var session = performGet("/api/interviews/sessions/" + sessionId, token);
                        if (session.getResponse().getStatus() != 200)
                                return session;
                        command.put(
                                        "expectedTurn",
                                        objectMapper
                                                        .readTree(
                                                                        session.getResponse()
                                                                                        .getContentAsString(
                                                                                                        StandardCharsets.UTF_8))
                                                        .path("data")
                                                        .path("turnCount")
                                                        .asInt());
                }
                return completeInterviewCommand(
                                performPost(
                                                "/api/interviews/sessions/" + sessionId + "/answer-commands",
                                                command,
                                                token),
                                token);
        }

        private MvcResult completeInterviewCommand(MvcResult accepted, String token) throws Exception {
                if (accepted.getResponse().getStatus() != 202)
                        return accepted;
                var run = objectMapper
                                .readTree(accepted.getResponse().getContentAsString(StandardCharsets.UTF_8))
                                .path("data");
                var profile = performGet("/api/users/me", token);
                org.assertj.core.api.Assertions.assertThat(profile.getResponse().getStatus())
                                .isEqualTo(200);
                long owner = objectMapper
                                .readTree(profile.getResponse().getContentAsString(StandardCharsets.UTF_8))
                                .path("data")
                                .path("id")
                                .asLong();
                var row = interviewRuns.get(owner, run.path("runId").asString());
                if (!row.terminal()) {
                        var claim = interviewRuns.claim(row);
                        org.assertj.core.api.Assertions.assertThat(claim)
                                        .as("Fixture run must be available for explicit execution")
                                        .isNotNull();
                        interviewWorker.execute(claim);
                        row = interviewRuns.get(owner, row.id());
                }
                org.assertj.core.api.Assertions.assertThat(row.status())
                                .as("Run %s failed with %s", row.id(), row.error())
                                .isEqualTo("SUCCEEDED");
                return performGet("/api/interviews/sessions/" + row.sessionId(), token);
        }

        protected MvcResult performPost(String url, Object body) throws Exception {
                return mockMvc.perform(
                                post(url)
                                                .contentType(
                                                                new MediaType(
                                                                                MediaType.APPLICATION_JSON,
                                                                                StandardCharsets.UTF_8))
                                                .content(
                                                                objectMapper
                                                                                .writeValueAsString(body)
                                                                                .getBytes(StandardCharsets.UTF_8)))
                                .andReturn();
        }

        protected MvcResult performPost(String url, Object body, String token) throws Exception {
                return mockMvc.perform(
                                post(url)
                                                .header("Authorization", "Bearer " + token)
                                                .contentType(
                                                                new MediaType(
                                                                                MediaType.APPLICATION_JSON,
                                                                                StandardCharsets.UTF_8))
                                                .content(
                                                                objectMapper
                                                                                .writeValueAsString(body)
                                                                                .getBytes(StandardCharsets.UTF_8)))
                                .andReturn();
        }

        protected MvcResult performGet(String url) throws Exception {
                return mockMvc.perform(get(url).characterEncoding(StandardCharsets.UTF_8.name()))
                                .andReturn();
        }

        protected MvcResult performGet(String url, String token) throws Exception {
                return mockMvc.perform(
                                get(url).header("Authorization", "Bearer " + token)
                                                .characterEncoding(StandardCharsets.UTF_8.name()))
                                .andReturn();
        }

        protected MvcResult performPut(String url, Object body, String token) throws Exception {
                return mockMvc.perform(
                                put(url).header("Authorization", "Bearer " + token)
                                                .contentType(
                                                                new MediaType(
                                                                                MediaType.APPLICATION_JSON,
                                                                                StandardCharsets.UTF_8))
                                                .content(
                                                                objectMapper
                                                                                .writeValueAsString(body)
                                                                                .getBytes(StandardCharsets.UTF_8)))
                                .andReturn();
        }

        protected MvcResult performDelete(String url, String token) throws Exception {
                return mockMvc.perform(
                                delete(url)
                                                .header("Authorization", "Bearer " + token)
                                                .characterEncoding(StandardCharsets.UTF_8.name()))
                                .andReturn();
        }

        protected MvcResult performDeleteWithBody(String url, Object body, String token)
                        throws Exception {
                return mockMvc.perform(
                                delete(url)
                                                .header("Authorization", "Bearer " + token)
                                                .contentType(
                                                                new MediaType(
                                                                                MediaType.APPLICATION_JSON,
                                                                                StandardCharsets.UTF_8))
                                                .content(
                                                                objectMapper
                                                                                .writeValueAsString(body)
                                                                                .getBytes(StandardCharsets.UTF_8)))
                                .andReturn();
        }

        protected Map<String, Object> parseJsonResult(MvcResult result) throws Exception {
                String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {
                });
        }

        protected String extractTokenFromAuthResponse(MvcResult result) throws Exception {
                Map<String, Object> response = parseJsonResult(result);
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                return (String) data.get("token");
        }

        protected MvcResult registerUser(String username, String password, String displayName)
                        throws Exception {
                return performPost(
                                "/api/auth/register",
                                Map.of(
                                                "username", username,
                                                "password", password,
                                                "displayName", displayName));
        }

        protected MvcResult loginUser(String username, String password) throws Exception {
                return performPost(
                                "/api/auth/login",
                                Map.of(
                                                "username", username,
                                                "password", password));
        }

        protected String registerAndLogin(String username, String password, String displayName)
                        throws Exception {
                registerUser(username, password, displayName);
                MvcResult loginResult = loginUser(username, password);
                return extractTokenFromAuthResponse(loginResult);
        }

        protected MvcResult registerUniqueUser(String password, String displayName) throws Exception {
                MvcResult registerResult;
                int attempts = 0;
                do {
                        String username = "u" + System.currentTimeMillis() + (int) (Math.random() * 10000);
                        registerResult = registerUser(username, password, displayName);
                        attempts++;
                } while (registerResult.getResponse().getStatus() == 409 && attempts < 5);

                if (registerResult.getResponse().getStatus() != 200) {
                        throw new RuntimeException(
                                        "注册失败: " + registerResult.getResponse().getContentAsString());
                }

                return registerResult;
        }

        protected String registerUniqueUserToken(String password, String displayName) throws Exception {
                return extractTokenFromAuthResponse(registerUniqueUser(password, displayName));
        }
}
