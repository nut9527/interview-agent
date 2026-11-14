package cn.edu.whu.zhizhida.middleware;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.TestDatabaseGuard;
import cn.edu.whu.zhizhida.ZhizhidaApplication;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Nested;

/** 同一套真实 HTTP、账号与统计夹具，分别验证进程重启、拆分负载和消费者恢复。 */
@EnabledIfEnvironmentVariable(named="RUN_MIDDLEWARE_TESTS",matches="true")
class SplitRuntimeIT {
    final JsonMapper json=new JsonMapper();
    final HttpClient client=HttpClient.newHttpClient();
    @org.junit.jupiter.api.AfterEach void closeClient() { client.close(); }
    final String namespace="zhizhida.test.split."+UUID.randomUUID();
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    public static class Node {
        public static void main(String[] args) {
            new SpringApplicationBuilder(ZhizhidaApplication.class).initializers(new TestDatabaseGuard()).run(args);
        }
    }
    record NodeProcess(Process process,int port) implements AutoCloseable {
        public void close() throws Exception {
            process.destroy();
            if(!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue(); }
        }
    }
    NodeProcess launch(String role) throws Exception {
        return launch(role, Map.of());
    }
    NodeProcess launch(String role, Map<String,String> overrides) throws Exception {
        int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+directory, "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),Node.class.getName(),
                "--spring.config.location=classpath:/application-it.yml,classpath:/application-"+role+".yml",
                "--server.port="+port, "--app.redis.namespace="+namespace, "--app.run-dispatch.namespace="+namespace,
                "--spring.rabbitmq.publisher-confirm-type=correlated", "--spring.rabbitmq.publisher-returns=true",
                "--spring.rabbitmq.listener.simple.prefetch=1", "--spring.rabbitmq.connection-timeout=2s",
                "--interview.runs.capacity.queue-seconds=90", "--knowledge.import.directory="+directory.resolve("imports-"+role),
                "--knowledge.import.shared-directory="+directory.resolve("shared-imports"),"--knowledge.dispatch.namespace="+namespace+".knowledge",
                "--spring.jmx.enabled=false"));
        overrides.forEach((key,value)->args.add("--"+key+"="+value));
        var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(directory.resolve(role+"-"+port+".log").toFile());
        var env=builder.environment();
        env.put("AI_MAX_CONCURRENT_CALLS","4");
        env.put("SPRING_RABBITMQ_HOST",MiddlewareApplicationIT.env("RABBITMQ_HOST","127.0.0.1"));
        env.put("SPRING_RABBITMQ_PORT",MiddlewareApplicationIT.env("RABBITMQ_PORT","5672"));
        env.put("SPRING_RABBITMQ_USERNAME",MiddlewareApplicationIT.env("RABBITMQ_USERNAME","zhizhida"));
        env.put("SPRING_RABBITMQ_PASSWORD",MiddlewareApplicationIT.env("RABBITMQ_PASSWORD","zhizhida-local"));
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST",MiddlewareApplicationIT.env("RABBITMQ_VHOST","/zhizhida-lab"));
        MiddlewareApplicationIT.redisProperties().forEach((key,value)->env.put(key.replace('.','_').replace('-','_').toUpperCase(Locale.ROOT),value.toString()));
        var node=new NodeProcess(builder.start(),port);
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
            while(System.nanoTime()<until && node.process().isAlive()) {
                try { if(request(port,"GET","/api/health/ready",null,null).statusCode()==200) return node; }
                catch(java.io.IOException ignored) { }
                Thread.sleep(200);
            }
            String log=Files.readString(directory.resolve(role+"-"+port+".log"));
            for(String key:List.of("IT_DATABASE_PASSWORD","RABBITMQ_PASSWORD","REDIS_CACHE_PASSWORD","REDIS_RATE_PASSWORD")) {
                String secret=System.getenv(key);
                if(secret!=null && !secret.isEmpty()) log=log.replace(secret,"[redacted]");
            }
            throw new AssertionError(role+" did not start: "+log.substring(Math.max(0,log.length()-8000)));
        } catch(Throwable ex) { node.close(); throw ex; }
    }
    HttpResponse<String> request(int port,String method,String path,String token,Object body) throws Exception {
        return request(port,method,path,token,body,5);
    }
    HttpResponse<String> request(int port,String method,String path,String token,Object body,int timeoutSeconds) throws Exception {
        return request(port, method, path, token, body, Duration.ofSeconds(timeoutSeconds));
    }
    HttpResponse<String> request(int port,String method,String path,String token,Object body,Duration timeout) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(timeout);
        if(token!=null) request.header("Authorization","Bearer "+token);
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static final Map<String,Object> FORMAL=Map.of("targetRole","Java 后端","totalRounds",3,"experienceMode","FORMAL");
    static final Map<String,Object> WITH_RESUME=Map.of("targetRole","Java 后端","resumeSummary","负责缓存项目","totalRounds",3);
    Accepted start(int port,Account account,Map<String,Object> configuration,int timeoutSeconds) throws Exception {
        long started=System.nanoTime();
        var response=request(port,"POST","/api/interviews/start-commands",account.token(),
                Map.of("requestId",UUID.randomUUID().toString(),"configuration",configuration),timeoutSeconds);
        String runId=data(response,202).path("runId").asText();
        return new Accepted(account,runId,(System.nanoTime()-started)/1e6);
    }
    void completed(int port,String token,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20); JsonNode result=null;
        while(System.nanoTime()<until) {
            result=json.readTree(request(port,"GET","/api/interview-runs/"+id,token,null).body()).path("data");
            if(result.path("status").asText().equals("SUCCEEDED")) return;
            if(Set.of("FAILED","CANCELLED","EXPIRED").contains(result.path("status").asText()))
                fail("Worker ended without success: "+result);
            Thread.sleep(100);
        }
        fail("Worker did not complete run: "+result);
    }
    String upload(int port,String token) throws Exception {
        String boundary="fixture"+UUID.randomUUID();
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.md\"\r\nContent-Type: text/plain\r\n\r\nshared file body\r\n--"+boundary+"--\r\n";
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/knowledge/imports/jobs"))
                    .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return json.readTree(response.body()).at("/data/id").asText();
        }
    }
    void parsed(int port,String token,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until) {
            var r=json.readTree(request(port,"GET","/api/knowledge/imports/jobs/"+id,token,null).body());
            if(r.at("/data/status").asText().equals("READY")) return;
            Thread.sleep(100);
        }
        fail("Worker did not parse shared file");
    }
    JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("IT_DATABASE_URL"),
                System.getenv("IT_DATABASE_USERNAME"),System.getenv("IT_DATABASE_PASSWORD")));
    }

    record Account(long owner,String token) {}
    Account register(int port,String prefix) throws Exception {
        var credentials=Map.of("username",prefix+"_"+UUID.randomUUID().toString().replace("-","").substring(0,16),
                "password","password123","displayName",prefix);
        data(request(port,"POST","/api/auth/register",null,credentials),200);
        String token=data(request(port,"POST","/api/auth/login",null,credentials),200).path("token").asText();
        return new Account(data(request(port,"GET","/api/users/me",token,null),200).path("id").asLong(), token);
    }

    JsonNode data(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).path("data");
    }

    @Test void apiQueuesWithoutWorkerThenIndependentWorkerExecutesAndCanRestart() throws Exception {
        var jdbc=database();
        Account account=null;
        try(var api=launch("api")) {
            account=register(api.port(),"split");
            String token=account.token();
            try {
                String first=start(api.port(),account,FORMAL,5).id();
                String firstImport=upload(api.port(),token);
                assertThat(jdbc.queryForObject("SELECT status FROM knowledge_import_jobs WHERE id=?",String.class,firstImport)).isEqualTo("QUEUED");
                Thread.sleep(700);
                assertThat(jdbc.queryForObject("SELECT status FROM interview_runs WHERE id=?",String.class,first)).isEqualTo("ACCEPTED");
                try(var worker=launch("worker")) {
                    assertThat(request(worker.port(),"POST","/api/auth/demo",null,Map.of()).statusCode()).isEqualTo(404);
                    completed(api.port(),token,first);
                    parsed(api.port(),token,firstImport);
                }
                String second=start(api.port(),account,FORMAL,5).id();
                String secondImport=upload(api.port(),token);
                assertThat(jdbc.queryForObject("SELECT status FROM interview_runs WHERE id=?",String.class,second)).isEqualTo("ACCEPTED");
                try(var restarted=launch("worker")) { completed(api.port(),token,second); parsed(api.port(),token,secondImport); }
            } finally { request(api.port(),"DELETE","/api/users/me",token,Map.of("currentPassword","password123")); }
        } finally {
            cleanupMiddleware(account==null ? List.of() : List.of(account));
        }
    }
    static Map<String, Object> summary(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return Map.of("count", sorted.size(), "mean", sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                "p50", sorted.get((int) Math.ceil(sorted.size() * .50) - 1),
                "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "max", sorted.getLast(), "samples", values);
    }

    final List<Account> accounts=new ArrayList<>();
    record Accepted(Account account,String id,double httpMs) {}
    @EnabledIfEnvironmentVariable(named="RUN_SPLIT_LOAD_TESTS",matches="true")
    @Test void compareWorkersAndReplayDurableProgressWithCancellation() throws Exception {
        var jdbc = database();
        var report = new LinkedHashMap<String,Object>();
        try (var upstream = new Upstream()) {
            var settings = Map.of("ai.provider", "deepseek", "ai.api-key", "synthetic-local-only",
                    "ai.deepseek.base-url", upstream.url(), "ai.deepseek.timeout-seconds", "25",
                    "interview.runs.decision-protocol", "phased-v2",
                    "ai.resilience.max-concurrent-calls", "6",
                    "app.outbox.publisher.batch-size", "8");
            try (var api = launch("api", settings)) {
                Throwable failure = null;
                try {
                    for(int i=0;i<8;i++) accounts.add(register(api.port(),"load"));
                    try (var first = launch("worker", settings)) {
                        report.put("oneWorker", measure(api.port(), jdbc, upstream, 2));
                        try (var second = launch("worker", settings)) {
                            report.put("twoWorkers", measure(api.port(), jdbc, upstream, 4));
                            checkSharedProgressAndCapacity(api.port(), jdbc, upstream, report);
                        }
                    }
                } catch (Exception | AssertionError ex) { failure = ex; throw ex;
                } finally {
                    for (var account : accounts) {
                        try { assertThat(request(api.port(), "DELETE", "/api/users/me", account.token(), Map.of("currentPassword", "password123")).statusCode()).isEqualTo(200); }
                        catch (Exception | AssertionError ex) { if (failure != null) failure.addSuppressed(ex); else throw ex; }
                    }
                }
            }
        } finally { cleanupMiddleware(accounts); }
        report.put("scope", "independent-jvms-real-mysql-redis-rabbitmq-synthetic-json-upstream-durable-sse");
        report.put("capacity", Map.of("sharedModelLimit", 6, "processes", 3,
                "workerPeakLimit", 4, "apiModelLoadIncluded", true, "syntheticFinalDelayMs", 600));
        Files.writeString(Path.of("target/split-runtime-load.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    void checkSharedProgressAndCapacity(int port,JdbcTemplate jdbc,Upstream upstream,Map<String,Object> report) throws Exception {
        // 总验证预算短于上游 gate 的 20 秒，且为模型的 25 秒期限保留响应与取消时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        upstream.reset(); upstream.gate = new CountDownLatch(1); upstream.workerCalls = new CountDownLatch(4);
        List<Accepted> accepted;
        var synchronous = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        try {
            accepted = burst(port, 4, deadline);
            assertThat(upstream.workerCalls.await(remaining(deadline), TimeUnit.NANOSECONDS)).as("both Workers hold two final model calls").isTrue();
            assertThat(upstream.peak.get()).isEqualTo(4);
            try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var progress = new ArrayList<Future<Double>>();
                var reads = new ArrayList<ProgressRead>();
                try {
                    for (var run : accepted) {
                        var read = new ProgressRead(); reads.add(read);
                        progress.add(readers.submit(() -> replayProgress(port, run, deadline, read)));
                    }
                    var timings = new ArrayList<Double>();
                    for (var result : progress) timings.add(result.get(remaining(deadline), TimeUnit.NANOSECONDS));
                    report.put("durableProgressReplayHttpMs", summary(timings));
                } finally {
                    progress.forEach(future -> future.cancel(true));
                    reads.forEach(ProgressRead::close);
                    readers.shutdownNow();
                }
            }
            upstream.apiCalls = new CountDownLatch(2);
            for (var account : accounts.subList(4, 6)) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/resume/optimize-draft"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + account.token())
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(Map.of("entries", List.of(Map.of("kind", "summary", "text", "负责缓存项目")), "targetRole", "Java 后端")))).build();
                synchronous.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            assertThat(upstream.apiCalls.await(remaining(deadline), TimeUnit.NANOSECONDS)).isTrue();
            assertThat(upstream.active.get()).as("API 2 + two Workers 4").isEqualTo(6);
            assertThat(request(port, "POST", "/api/ai/resume/optimize-draft", accounts.get(6).token(),
                    Map.of("entries", List.of(Map.of("kind", "summary", "text", "负责缓存项目")), "targetRole", "Java 后端"), Duration.ofNanos(remaining(deadline))).statusCode()).isEqualTo(503);
            assertThat(upstream.peak.get()).isEqualTo(6);
            report.put("aggregateProviderPeak", 6);
            report.put("apiOverloadRejectedBeforeProvider", true);
            var canceled = accepted.getFirst();
            assertThat(request(port, "POST", "/api/interview-runs/" + canceled.id() + "/cancel", canceled.account().token(), Map.of(), Duration.ofNanos(remaining(deadline))).statusCode()).isEqualTo(200);
        } finally { upstream.gate.countDown(); }
        for (var response : synchronous) assertThat(response.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        for (var run : accepted.subList(1, accepted.size())) completed(port, run.account().token(), run.id());
        awaitIdle(upstream);
        assertThat(jdbc.queryForObject("SELECT status FROM interview_runs WHERE id=?", String.class, accepted.getFirst().id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_events WHERE run_id=? AND type='turn.committed'", Integer.class, accepted.getFirst().id())).isZero();
        for (var run : accepted.subList(1, accepted.size())) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_events WHERE run_id=? AND type='turn.committed'", Integer.class, run.id())).isEqualTo(1);
        }
        report.put("inFlightCancellation", "PASSED");
    }

    List<Accepted> burst(int port, int size) throws Exception {
        return burst(port, size, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }
    List<Accepted> burst(int port, int size, long deadline) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<Accepted>>();
            try {
                for (var account : accounts.subList(0, size)) futures.add(executor.submit(() -> {
                    gate.await();
                    return start(port,account,WITH_RESUME,5);
                }));
                gate.countDown(); var accepted = new ArrayList<Accepted>();
                for (var future : futures) accepted.add(future.get(remaining(deadline), TimeUnit.NANOSECONDS));
                return accepted;
            } finally { futures.forEach(future -> future.cancel(true)); }
        }
    }
    Map<String,Object> measure(int port, JdbcTemplate jdbc, Upstream upstream, int limit) throws Exception {
        upstream.reset(); long started = System.nanoTime(); var accepted = burst(port, 8);
        for (var run : accepted) completed(port, run.account().token(), run.id());
        awaitIdle(upstream);
        double elapsed = (System.nanoTime() - started) / 1e9;
        assertThat(upstream.peak.get()).isBetween(1, limit);
        if (limit == 4) assertThat(upstream.peak.get()).as("second Worker contributes concurrent execution").isGreaterThan(2);
        assertThat(upstream.sends.get()).isEqualTo(16); // 每次运行仅有 ACTION 与 FINAL 两次调用，不能包含隐式模型重试。
        var queue = new ArrayList<Double>(); var total = new ArrayList<Double>();
        for (var run : accepted) {
            queue.add(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,created_at,first_started_at)/1000.0 FROM interview_runs WHERE id=?", Double.class, run.id()));
            total.add(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,created_at,updated_at)/1000.0 FROM interview_runs WHERE id=?", Double.class, run.id()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_events WHERE run_id=? AND type='turn.committed'", Integer.class, run.id())).isEqualTo(1);
        }
        return Map.of("completed", 8, "throughputPerSecond", 8 / elapsed, "elapsedSeconds", elapsed,
                "peakProviderCalls", upstream.peak.get(), "providerSends", upstream.sends.get(),
                "httpMs", summary(accepted.stream().map(Accepted::httpMs).toList()),
                "queueMs", summary(queue), "endToEndMs", summary(total));
    }
    static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("Shared progress verification exceeded its total deadline");
        return nanos;
    }
    /** 协调线程关闭正在读的响应；即使取消发生在响应到达前，也不能留下未关闭的流。 */
    static final class ProgressRead implements AutoCloseable {
        private InputStream stream;
        private boolean closed;
        synchronized void attach(InputStream value) throws IOException {
            if (closed) { value.close(); throw new IOException("Progress read cancelled"); }
            stream = value;
        }
        synchronized void detach() { stream = null; }
        @Override public void close() {
            InputStream current;
            synchronized (this) { closed = true; current = stream; stream = null; }
            if (current != null) try { current.close(); } catch (IOException ignored) {
                // 读者的 try-with-resources 仍会关闭响应；取消清理不得掩盖原始断言或超时。
            }
        }
    }
    double replayProgress(int port, Accepted run, long deadline, ProgressRead read) throws Exception {
        long started = System.nanoTime();
        long action = modelStarted(port, run, 0, deadline, read);
        long finalCall = modelStarted(port, run, action, deadline, read);
        assertThat(finalCall).as("Last-Event-ID resumes after the ACTION attempt").isGreaterThan(action);
        return (System.nanoTime() - started) / 1e6;
    }
    long modelStarted(int port, Accepted run, long after, long deadline, ProgressRead read) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/interview-runs/" + run.id() + "/events"))
                .timeout(Duration.ofNanos(remaining(deadline))).header("Authorization", "Bearer " + run.account().token())
                .header("Last-Event-ID", Long.toString(after)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            read.attach(response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            String line; long eventId = 0, last = after;
            while ((line = reader.readLine()) != null) {
                assertThat(line).doesNotContain("PRIVATE_REASONING_SENTINEL");
                if (line.startsWith("id:")) eventId = Long.parseLong(line.substring(3).trim());
                if (line.startsWith("data:")) {
                    var event = json.readTree(line.substring(5).trim());
                    assertThat(event.path("runId").asText()).isEqualTo(run.id());
                    assertThat(event.path("seq").asLong()).isEqualTo(eventId).isGreaterThan(last);
                    last = eventId;
                    if (event.path("type").asText().equals("model.started")) return eventId;
                }
            }
        } finally { read.detach(); }
        throw new AssertionError("No persisted model progress from Worker");
    }
    void awaitIdle(Upstream upstream) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (upstream.active.get() != 0 && System.nanoTime() < until) Thread.sleep(20);
        assertThat(upstream.active.get()).isZero();
    }
    void cleanupMiddleware(List<Account> accounts) {
        var config = new RedisStandaloneConfiguration(MiddlewareApplicationIT.env("REDIS_RATE_HOST", "127.0.0.1"),
                Integer.parseInt(MiddlewareApplicationIT.env("REDIS_RATE_PORT", "6379")));
        config.setPassword(MiddlewareApplicationIT.env("REDIS_RATE_PASSWORD", ""));
        var factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        try {
            var redis = new StringRedisTemplate(factory);
            redis.delete(List.of(namespace + ":{ai-call}:leases", namespace + ":{ai-call}:circuit", namespace + ":{sse}:all",
                    "{" + namespace + ":run-retention}:lease", "{" + namespace + ":run-retention}:cursor"));
            for (var account : accounts) {
                redis.delete(namespace + ":{sse}:owner:" + account.owner());
                redis.delete(namespace + ":rate:ai-submit:" + account.owner());
            }
        } finally { factory.destroy(); }
        var rabbit = new CachingConnectionFactory(MiddlewareApplicationIT.env("RABBITMQ_HOST", "127.0.0.1"), Integer.parseInt(MiddlewareApplicationIT.env("RABBITMQ_PORT", "5672")));
        rabbit.setUsername(MiddlewareApplicationIT.env("RABBITMQ_USERNAME", "zhizhida")); rabbit.setPassword(MiddlewareApplicationIT.env("RABBITMQ_PASSWORD", "zhizhida-local"));
        rabbit.setVirtualHost(MiddlewareApplicationIT.env("RABBITMQ_VHOST", "/zhizhida-lab"));
        try {
            var admin = new RabbitAdmin(rabbit);
            for (String q : List.of(".run.q", ".dead.q", ".knowledge.import.q", ".knowledge.index.q", ".knowledge.dead.q")) admin.deleteQueue(namespace + q);
            for (String x : List.of(".x", ".dlx", ".knowledge.x", ".knowledge.dlx")) admin.deleteExchange(namespace + x);
        } finally { rabbit.destroy(); }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "RUN_MIDDLEWARE_BENCHMARK", matches = "true")
    @DirtiesContext
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "app.cache.questions.enabled=true", "app.rate-limit.redis.enabled=true", "app.rate-limit.redis.limit=100",
            "app.run-dispatch.mode=rabbitmq", "interview.runs.worker-enabled=true",
            "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.connection-timeout=2s", "spring.rabbitmq.listener.simple.prefetch=1",
            "app.outbox.publisher.interval-ms=500", "app.run-recovery.interval-ms=5000",
            "interview.runs.capacity.max-active=4", "interview.runs.capacity.max-outstanding=20",
            "interview.runs.capacity.queue-seconds=10"})
    class Benchmark extends IntegrationTestBase {
        static final String namespace = "zhizhida.test.benchmark." + UUID.randomUUID();
        static final int publisherInterval = setting("BENCHMARK_PUBLISHER_INTERVAL_MS",500,1,500);
        static final int publisherBatchSize = setting("BENCHMARK_PUBLISHER_BATCH_SIZE",1,1,32);
        static final int publisherBatchBudget = setting("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS",100,1,1000);
        static int setting(String key,int fallback,int minimum,int maximum) {
            int value=Integer.parseInt(System.getenv().getOrDefault(key,Integer.toString(fallback)));
            if(value<minimum || value>maximum) throw new IllegalArgumentException(key+" must be "+minimum+".."+maximum);
            return value;
        }
        @DynamicPropertySource static void middleware(DynamicPropertyRegistry p) {
            p.add("app.outbox.publisher.interval-ms", () -> publisherInterval);
            p.add("app.outbox.publisher.batch-size", () -> publisherBatchSize);
            p.add("app.outbox.publisher.batch-budget-ms", () -> publisherBatchBudget);
            p.add("app.redis.namespace", () -> namespace);
            p.add("app.run-dispatch.namespace", () -> namespace);
            MiddlewareApplicationIT.redisProperties().forEach((key, value) -> p.add(key, () -> value));
            p.add("spring.rabbitmq.host", () -> MiddlewareApplicationIT.env("RABBITMQ_HOST", "127.0.0.1"));
            p.add("spring.rabbitmq.port", () -> MiddlewareApplicationIT.env("RABBITMQ_PORT", "5672"));
            p.add("spring.rabbitmq.username", () -> MiddlewareApplicationIT.env("RABBITMQ_USERNAME", "zhizhida"));
            p.add("spring.rabbitmq.password", () -> MiddlewareApplicationIT.env("RABBITMQ_PASSWORD", "zhizhida-local"));
            p.add("spring.rabbitmq.virtual-host", () -> MiddlewareApplicationIT.env("RABBITMQ_VHOST", "/zhizhida-lab"));
        }
        @Autowired JdbcTemplate jdbc;
        @Autowired QuestionCatalog catalog;
        @Autowired RunDispatchOutbox outbox;
        @Autowired MeterRegistry metrics;
        @Autowired RabbitListenerEndpointRegistry listeners;
        @Autowired CachingConnectionFactory rabbit;
        @Autowired @Qualifier("questionCacheRedisTemplate") StringRedisTemplate cache;
        @Autowired @Qualifier("submissionRedisTemplate") StringRedisTemplate rate;
        @LocalServerPort int port;
        final Map<String, Object> report = new LinkedHashMap<>();
        List<Accepted> burst(int count) throws Exception {
            try (var executor = Executors.newFixedThreadPool(count)) {
                var gate = new CountDownLatch(1);
                var futures = new ArrayList<Future<Accepted>>();
                for (var account : accounts.subList(0, count)) futures.add(executor.submit(() -> {
                    assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
                    return start(port,account,FORMAL,10);
                }));
                gate.countDown();
                var result = new ArrayList<Accepted>();
                for (var future : futures) result.add(future.get(15, TimeUnit.SECONDS));
                return result;
            }
        }
        List<Map<String, Object>> rows(List<Accepted> accepted) {
            return accepted.stream().map(run -> jdbc.queryForMap("SELECT status,"
                    + "TIMESTAMPDIFF(MICROSECOND,created_at,first_started_at)/1000.0 AS queue_ms,"
                    + "TIMESTAMPDIFF(MICROSECOND,first_started_at,updated_at)/1000.0 AS execution_ms,"
                    + "TIMESTAMPDIFF(MICROSECOND,created_at,updated_at)/1000.0 AS total_ms "
                    + "FROM interview_runs WHERE id=?", run.id())).toList();
        }
        Map<String, Object> drain(List<Accepted> accepted) throws Exception {
            long start = System.nanoTime(), until = start + TimeUnit.SECONDS.toNanos(25);
            double oldest = 0;
            long pending = 0;
            while (true) {
                pending = Math.max(pending, outbox.pending());
                oldest = Math.max(oldest, outbox.oldestPendingSeconds());
                var rows = rows(accepted);
                if (rows.stream().noneMatch(r -> Set.of("ACCEPTED", "RUNNING").contains(r.get("status")))) {
                    assertThat(rows).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SUCCEEDED"));
                    assertThat(outbox.pending()).isZero();
                    return Map.of("completed", rows.size(), "drainMs", (System.nanoTime() - start) / 1e6,
                            "sampledMaxPending", pending, "sampledOldestPendingSeconds", oldest,
                            "acceptHttpMs", summary(accepted.stream().map(Accepted::httpMs).toList()),
                            "queueMs", column(rows, "queue_ms"), "executionMs", column(rows, "execution_ms"),
                            "endToEndMs", column(rows, "total_ms"));
                }
                assertThat(System.nanoTime()).as("bounded benchmark drain").isLessThan(until);
                Thread.sleep(50);
            }
        }
        static Map<String, Object> column(List<Map<String, Object>> rows, String key) {
            return summary(rows.stream().map(r -> ((Number) r.get(key)).doubleValue()).toList());
        }
        double counter(String outcome) { return metrics.counter("zhizhida.question.cache", "outcome", outcome).count(); }
        Map<String, Object> cacheSamples(String key, int version, boolean cold) {
            var durations = new ArrayList<Double>();
            double sources = counter("source"), hits = counter("hit");
            for (int i = 0; i < 50; i++) {
                if (cold) cache.delete(key); // 淘汰缓存的耗时不计入查询时间。
                long start = System.nanoTime();
                catalog.requirePublishedForExecution(accounts.getFirst().owner(), "qb-java-001", version);
                durations.add((System.nanoTime() - start) / 1e6);
            }
            double sourceReads = counter("source") - sources, cacheHits = counter("hit") - hits;
            assertThat(sourceReads).isEqualTo(cold ? 50 : 0);
            assertThat(cacheHits).isEqualTo(cold ? 0 : 50);
            return Map.of("catalogMs", summary(durations), "bodySourceReads", sourceReads, "hits", cacheHits, "hitRatio", cacheHits / 50);
        }
        void measureWarmSourceCreation(int version) throws Exception {
            // 快照缓存用于创建训练来源；GET /questions/{id} 不经过此缓存。
            var httpSamples = new ArrayList<Double>();
            double beforeHits = counter("hit"), beforeSources = counter("source");
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                var task = data(request(port, "POST", "/api/interviews/training-tasks/from-source", accounts.getFirst().token(),
                        Map.of("requestId", UUID.randomUUID().toString(), "sourceType", "QUESTION", "questionId", "qb-java-001", "questionVersion", version)), 200);
                httpSamples.add((System.nanoTime() - start) / 1e6);
                data(request(port, "DELETE", "/api/interviews/training-tasks/" + task.path("id").asText(), accounts.getFirst().token(), null), 200);
            }
            assertThat(counter("source") - beforeSources).isZero();
            assertThat(counter("hit") - beforeHits).isEqualTo(20);
            report.put("warmSourceCreationHttpMs", summary(httpSamples));
        }

        void measureBurstWaves() throws Exception {
            drain(burst(4)); // 预热流水线，不计入后续测量。
            var waves = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                var result = new LinkedHashMap<>(drain(burst(12)));
                double seconds = (System.nanoTime() - start) / 1e9;
                result.put("wallSeconds", seconds);
                result.put("completedPerSecond", 12 / seconds);
                waves.add(result);
            }
            report.put("burstWaves", waves);
        }

        void measureConsumerRecovery() throws Exception {
            listeners.getListenerContainers().forEach(container -> container.stop());
            try {
                var queued = burst(6);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                while (outbox.pending() != 0) {
                    assertThat(System.nanoTime()).as("outbox publish while consumers stopped").isLessThan(until);
                    Thread.sleep(50);
                }
                Thread.sleep(1000);
                assertThat(rows(queued)).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("ACCEPTED"));
                long resume = System.nanoTime();
                listeners.getListenerContainers().forEach(container -> container.start());
                var result = new LinkedHashMap<>(drain(queued));
                result.put("resumeToCompletedMs", (System.nanoTime() - resume) / 1e6);
                result.put("fault", "consumer containers stopped; broker and application remain alive");
                report.put("consumerRecovery", result);
            } finally { listeners.getListenerContainers().forEach(container -> container.start()); }
        }

        @Test void measureCacheBurstAndConsumerRecovery() throws Exception {
            String snapshotKey = null;
            try {
                for(int i=0;i<12;i++) accounts.add(register(port,"bench"));
                int version = jdbc.queryForObject("SELECT version FROM question_bank WHERE id='qb-java-001'", Integer.class);
                snapshotKey = namespace + ":question:snapshot:s1:qb-java-001:v" + version;
                for (int i = 0; i < 10; i++) catalog.requirePublishedForExecution(accounts.getFirst().owner(), "qb-java-001", version);
                report.put("schemaVersion", 1);
                report.put("environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                        "availableProcessors", Runtime.getRuntime().availableProcessors(), "model", "mock", "processes", 1,
                        "publisherIntervalMs", publisherInterval, "consumerConcurrency", 2, "prefetch", 1, "queueDeadlineSeconds", 10));
                report.put("publisher",Map.of("intervalMs",publisherInterval,"batchSize",publisherBatchSize,
                        "batchBudgetMs",publisherBatchBudget,"maxInFlight",1));
                report.put("coldCache", cacheSamples(snapshotKey, version, true));
                report.put("warmCache", cacheSamples(snapshotKey, version, false));
                measureWarmSourceCreation(version);
                measureBurstWaves();
                measureConsumerRecovery();
                report.put("limitations", List.of("Local single-process middleware baseline with mock AI; not production capacity or provider throughput",
                        "Three waves of 12; nearest-rank percentiles; retain samples; no latency assertions",
                        "Database polling every 50 ms adds observer overhead; pending maxima are sampled after submission",
                        "Cold cache includes Redis miss/write plus database body read; warm cache still checks publication/version in MySQL",
                        "Consumer recovery excludes JVM restart and broker restart; split-process correctness is covered by SplitRuntimeIT"));
            } finally {
                listeners.getListenerContainers().forEach(container -> container.stop());
                try {
                    for (var account : accounts) {
                        data(request(port, "DELETE", "/api/users/me", account.token(), Map.of("currentPassword", "password123")), 200);
                        rate.delete(namespace + ":rate:ai-submit:" + account.owner());
                    }
                } finally {
                    if (snapshotKey != null) cache.delete(snapshotKey);
                    var admin = new RabbitAdmin(rabbit);
                    admin.deleteQueue(namespace + ".run.q"); admin.deleteQueue(namespace + ".dead.q");
                    admin.deleteQueue(namespace + ".retry.q"); admin.deleteExchange(namespace + ".retry.x");
                    admin.deleteExchange(namespace + ".x"); admin.deleteExchange(namespace + ".dlx");
                }
            }
            // 所有断言与清理成功后才写出新结果，报告中不得包含账号或凭据。
            Files.writeString(Path.of("target/middleware-benchmark.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
