package cn.edu.whu.zhizhida.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RuntimeRoleConfigurationTest {
    MockEnvironment role(String role) throws Exception {
        var env=new MockEnvironment();
        new YamlPropertySourceLoader().load(role,new ClassPathResource("application-"+role+".yml"))
                .forEach(p->env.getPropertySources().addLast(p));
        return env;
    }
    @Test void shippedProfilesValidateAndFailClosedOnConflictingOverrides() throws Exception {
        var api=role("api"); var worker=role("worker");
        RuntimeRoleConfiguration.validate(api); RuntimeRoleConfiguration.validate(worker);
        RuntimeRoleConfiguration.validate(new MockEnvironment());
        assertThatThrownBy(()->RuntimeRoleConfiguration.validate(api.withProperty("interview.runs.worker-enabled","true")))
                .hasMessageContaining("worker conflicts");
        assertThatThrownBy(()->RuntimeRoleConfiguration.validate(worker.withProperty("knowledge.import.worker-enabled","false")))
                .hasMessageContaining("Import worker");
    }
    @Test void splitRolesRejectInvalidSharedCapacity() throws Exception {
        var worker=role("worker");
        assertThatThrownBy(()->RuntimeRoleConfiguration.validate(worker.withProperty("ai.resilience.max-concurrent-calls","0")))
                .hasMessageContaining("Shared model concurrency");
        RuntimeRoleConfiguration.validate(worker.withProperty("ai.resilience.max-concurrent-calls","1"));
    }
    @Test void experimentalPoliciesAreConfinedToEvaluation() throws Exception {
        for (String policy : new String[]{"B0", "S1", "S3"}) {
            var evaluation = new MockEnvironment().withProperty("interview.runs.loop-policy", policy);
            assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(evaluation))
                    .hasMessageContaining("agent-evaluation");
            evaluation.setActiveProfiles("agent-evaluation");
            RuntimeRoleConfiguration.validate(evaluation);
            for (String name : new String[]{"api", "worker"}) {
                var deployed = role(name).withProperty("interview.runs.loop-policy", policy);
                deployed.setActiveProfiles("agent-evaluation");
                assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(deployed))
                        .hasMessageContaining("API/Worker");
            }
        }
        var unsupported = new MockEnvironment().withProperty("interview.runs.loop-policy", "S5");
        unsupported.setActiveProfiles("agent-evaluation");
        assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(unsupported))
                .hasMessageContaining("Unsupported interview loop policy");
    }
    @Test void comparisonRoutesRequireAnExplicitTestOrEvaluationProfile() throws Exception {
        for (String route : new String[]{"FIXED", "LEGACY"}) {
            var env = new MockEnvironment().withProperty("interview.runs.route", route);
            assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(env)).hasMessageContaining("agent-evaluation");
            for (String profile : new String[]{"test", "it", "agent-evaluation"}) {
                env.setActiveProfiles(profile);
                RuntimeRoleConfiguration.validate(env);
            }
            var worker = role("worker").withProperty("interview.runs.route", route);
            worker.setActiveProfiles("agent-evaluation");
            assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(worker)).hasMessageContaining("API/Worker");
        }
        assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(new MockEnvironment()
                .withProperty("interview.runs.route", "UNKNOWN"))).hasMessageContaining("Unsupported interview route");
    }
    @Test void retiredDecisionProtocolIsRejectedInEveryProfile() {
        for (String profile : new String[]{"production", "test", "it", "agent-evaluation"}) {
            var env = new MockEnvironment().withProperty("interview.runs.decision-protocol", "legacy");
            env.setActiveProfiles(profile);
            assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(env)).hasMessageContaining("Only phased-v2");
        }
    }
    @Test void adaptiveEvidenceBudgetCanRunInBothProductionRoles() throws Exception {
        RuntimeRoleConfiguration.validate(new MockEnvironment().withProperty("interview.runs.loop-policy", "A1")
                .withProperty("interview.runs.decision-protocol", "phased-v2"));
        for (String name : new String[]{"api", "worker"}) {
            RuntimeRoleConfiguration.validate(role(name).withProperty("interview.runs.loop-policy", "A1")
                    .withProperty("interview.runs.decision-protocol", "phased-v2"));
        }
    }
    @Test void workerExposesOnlyHealthAndTheExistingProtectedMetricsRoute() throws Exception {
        var filter=new RuntimeRoleConfiguration(role("worker")).workerHttpBoundary().getFilter();
        for(String path:new String[]{"/api/auth/demo","/api/interviews/start-commands","/api/knowledge/imports/jobs","/v3/api-docs"}) {
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST",path),response,(request,result)->{throw new AssertionError("Business route reached");});
            assertThat(response.getStatus()).isEqualTo(404);
        }
        for(String path:new String[]{"/api/health","/api/health/ready","/actuator/prometheus"}) {
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET",path),response,(request,result)->result.getWriter().write("security chain"));
            assertThat(response.getContentAsString()).isEqualTo("security chain");
        }
    }

    @Test void capacityDoesNotMultiplyWithReplicaCount() throws Exception {
        for (String name : new String[]{"api", "worker"}) {
            RuntimeRoleConfiguration.validate(role(name)
                    .withProperty("ai.resilience.max-concurrent-calls", "4"));
        }
        assertThatThrownBy(() -> RuntimeRoleConfiguration.validate(new MockEnvironment()
                .withProperty("app.redis.namespace", " "))).hasMessageContaining("namespace");
    }
}
