package cn.edu.whu.zhizhida.config;

import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 在启动恢复任务前校验 API／Worker 的职责配置，避免重复消费或缺少共享协调资源。 */
@Configuration(proxyBeanMethods=false)
public class RuntimeRoleConfiguration {
    public RuntimeRoleConfiguration(Environment env) { validate(env); }

    static void validate(Environment env) {
        String role=env.getProperty("app.runtime.role","all");
        require(Set.of("all","api","worker").contains(role), "Unsupported runtime role");
        String loopPolicy=env.getProperty("interview.runs.loop-policy","A1");
        require(Set.of("A1","B0","S1","S3").contains(loopPolicy), "Unsupported interview loop policy");
        String route=env.getProperty("interview.runs.route","TOOL");
        require(Set.of("TOOL","FIXED","LEGACY").contains(route), "Unsupported interview route");
        require("phased-v2".equals(env.getProperty("interview.runs.decision-protocol","phased-v2")),
                "Only phased-v2 interview decision protocol is supported");
        if(!"A1".equals(loopPolicy) || !"TOOL".equals(route))
            require("all".equals(role) && env.matchesProfiles("test", "it", "agent-evaluation"),
                    "Experimental interview routes and policies require test/it/agent-evaluation and cannot run in API/Worker roles");
        if(Set.of("S1","S3").contains(loopPolicy))
            require("all".equals(role) && env.matchesProfiles("agent-evaluation"),
                    "S1/S3 require the agent-evaluation profile and cannot run in API/Worker roles");
        String knowledgeMode=env.getProperty("knowledge.dispatch.mode","local");
        require(Set.of("local","rabbitmq").contains(knowledgeMode), "Unsupported knowledge dispatch mode");
        if("rabbitmq".equals(knowledgeMode)) {
            require("rabbitmq".equals(env.getProperty("app.run-dispatch.mode")), "Knowledge MQ requires middleware mode");
            require(env.getProperty("knowledge.import.concurrency",Integer.class,2)>0
                    && env.getProperty("knowledge.index.concurrency",Integer.class,1)>0, "Knowledge concurrency must be positive");
            long seconds=env.getProperty("knowledge.import.timeout-seconds",Long.class,180L);
            require(seconds>0 && seconds<=1800,"Import deadline must be within the 30 minute retention window");
        }
        require(env.getProperty("ai.resilience.max-concurrent-calls",Integer.class,4)>0,
                "Shared model concurrency must be positive");
        require(!env.getProperty("app.redis.namespace","zhizhida:dev").isBlank(),
                "Redis coordination namespace must not be blank");
        if("all".equals(role)) return;
        require("rabbitmq".equals(env.getProperty("app.run-dispatch.mode")), "Split roles require RabbitMQ dispatch");
        boolean worker="worker".equals(role);
        require(flag(env,"interview.runs.worker-enabled",true)==worker, "Run worker conflicts with runtime role");
        require(flag(env,"learning.worker-enabled",true)==worker, "Learning worker conflicts with runtime role");
        require(flag(env,"agent.vector.worker-enabled",true)==worker, "Index worker conflicts with runtime role");
        require(flag(env,"app.run-recovery.enabled",true)==worker, "Run recovery conflicts with runtime role");
        if("rabbitmq".equals(knowledgeMode)) {
            require(flag(env,"knowledge.import.worker-enabled",true)==worker, "Import worker conflicts with runtime role");
            require(flag(env,"knowledge.dispatch.publisher-enabled",true)!=worker, "Knowledge publishing must run on API role");
        } else require(flag(env,"knowledge.import.worker-enabled",true)!=worker, "Local imports must run on API role only");
        require(flag(env,"app.outbox.publisher.enabled",true)!=worker, "Outbox publishing must run on API role");

    }
    private static boolean flag(Environment e,String key,boolean fallback) { return e.getProperty(key,Boolean.class,fallback); }
    private static void require(boolean valid,String message) { if(!valid) throw new IllegalArgumentException(message); }

    @Bean
    @ConditionalOnProperty(name="app.runtime.role",havingValue="worker")
    FilterRegistrationBean<OncePerRequestFilter> workerHttpBoundary() {
        var registration=new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                    throws ServletException,IOException {
                String path=request.getRequestURI().substring(request.getContextPath().length());
                if(Set.of("/api/health","/api/health/ready","/actuator/prometheus").contains(path)) chain.doFilter(request,response);
                else response.setStatus(404);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
