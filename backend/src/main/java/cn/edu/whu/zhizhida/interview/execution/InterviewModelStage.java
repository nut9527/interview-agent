package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.AgentExecution;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.AgentTelemetry.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;

final class InterviewModelStage {
    private static final Logger log = LoggerFactory.getLogger(InterviewRunWorker.class);
    private final InterviewRunStore store;
    private final InterviewRunModel model;
    private final InterviewEvidenceTools tools;
    private final ObjectMapper json;
    private final AgentTelemetry telemetry;
    private final RunEvidence evidence;

    InterviewModelStage(
            InterviewRunStore store,
            InterviewRunModel model,
            InterviewEvidenceTools tools,
            ObjectMapper json,
            AgentTelemetry telemetry,
            RunEvidence evidence) {
        this.store = store;
        this.model = model;
        this.tools = tools;
        this.json = json;
        this.telemetry = telemetry;
        this.evidence = evidence;
    }

    boolean callModel(RunContext execution, InterviewRunStore.Row row, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var policy = execution.budget().execution();
        var memorySelection = execution.memorySelection();
        var session = execution.session();
        var pending = execution.pending();
        var context = execution.context();
        var budget = execution.budget();
        long remaining = store.remainingMillis(c.id());
        AgentExecution.requireTime(
                remaining, AgentLoopPolicy.MIN_MODEL_BUDGET_MILLIS, "RUN_BUDGET");
        if (cp.path("outputPhase").asString().equals("ACTION")) {
            boolean exhausted =
                    row.attempts() >= budget.attemptLimit("ACTION", false)
                            || remaining
                                    < budget.actionMinimumMillis()
                                            + AgentLoopPolicy.COMMIT_RESERVE_MILLIS;
            if (exhausted) {
                if (cp.path("actionRepaired").asBoolean())
                    throw new RunFailureException("MODEL_ACTION_INVALID");
                enterFinal(execution);
            }
        }
        if (policy.explicit() && !cp.path("loop").has("stopReason")) {
            String boundary =
                    policy.boundary(
                            cp.path("loop").path("steps").asInt(),
                            row.attempts(),
                            row.batches(),
                            row.tools(),
                            remaining);
            if (boundary != null) cp.withObject("loop").put("stopReason", boundary);
        }
        if (policy.explicit()
                && cp.path("loop").has("stopReason")
                && !cp.path("loop").path("closed").asBoolean()) {
            cp.withObject("loop").put("closed", true);
            cp.withArray("messages")
                    .add(
                            json.createObjectNode()
                                    .put("role", "user")
                                    .put(
                                            "content",
                                            InterviewRunModel.stopPrompt(
                                                    cp.path("loop").path("stopReason").asString())));
            store.checkpoint(c, cp.toString(), "MODEL", "loop.stopped", "已停止继续取证，正在核对已有结果");
        }
        // 为最终输出保留一次模型尝试；修正输出或触及停止条件后不再开放工具。
        boolean allowed =
                row.route().equals("TOOL")
                        && !cp.path("repaired").asBoolean()
                        && row.batches() < policy.maxToolBatches()
                        && row.tools() < policy.maxToolExecutions()
                        && row.attempts() < policy.maxModelAttempts() - 1;
        if (policy.explicit() && cp.path("loop").has("stopReason")) allowed = false;
        // 阶段选择前也应用显式步边界，修正不得重新开放工具。
        // 工具停止后仍留一次行动调用检查已有证据，再进入 FINAL。
        allowed =
                allowed
                        && cp.path("outputPhase").asString().equals("ACTION")
                        && !cp.path("actionRepaired").asBoolean()
                        && row.attempts() < budget.attemptLimit("ACTION", false) - 1;
        long timeout =
                budget.callTimeout(
                        cp.path("outputPhase").asString(),
                        cp.path("repaired").asBoolean(),
                        remaining);
        var verified = evidence.verifiedReviewSources(execution);
        if (!verified.isEmpty())
            model.projectRetrievedSources(
                    c.owner(), session, pending, context, verified, cp.withArray("messages"));
        List<JsonNode> messages = json.convertValue(cp.path("messages"), new TypeReference<>() {});
        List<JsonNode> definitions = allowed ? tools.definitions(memorySelection) : List.of();
        // 预占尝试前编译输入，被拒输入不消耗模型或全局请求名额。
        var prepared =
                new RunContextAssembler(json)
                        .assemble(row.config(), messages, definitions, allowed);
        var reservation = store.reserveModelAttempt(c, prepared);
        String attempt = reservation.id();
        timeout = Math.min(timeout, reservation.timeoutMillis());
        trace.attemptId = attempt;
        log.info(
                "run.model_started runId={} attemptId={} epoch={} phase=MODEL",
                c.id(),
                attempt,
                c.epoch());
        long started = System.nanoTime();
        InterviewRunModel.Reply reply;
        var modelConfig = json.readTree(row.config());
        try (var modelSpan =
                telemetry
                        .start(Stage.MODEL)
                        .tag("agent.run.id", c.id())
                        .tag("agent.attempt.id", attempt)
                        .tag("agent.output.phase", cp.path("outputPhase").asString())
                        .tag("gen_ai.operation.name", "chat")
                        .tag("gen_ai.provider.name", modelConfig.path("provider").asString())
                        .tag(
                                "gen_ai.request.model",
                                modelConfig.path("model").path("model").asString())
                        .tag(
                                "agent.protocol.version",
                                modelConfig.path("model").path("protocolVersion").asString())
                        .tag("agent.usage.status", "MISSING")) {
            try {
                reply =
                        model.call(
                                row.config(),
                                prepared.messages(),
                                prepared.tools(),
                                allowed,
                                timeout,
                                attempt,
                                session,
                                pending,
                                context,
                                cp.path("outputPhase").asString());
                modelSpan.usage(reply.usage());
            } catch (RuntimeException ex) {
                String error = InterviewRunWorker.modelFailureCode(ex);
                modelSpan.failure(error);
                long elapsed = InterviewRunWorker.elapsedMillis(started);
                log.warn(
                        "run.model_failed runId={} attemptId={} epoch={} phase=MODEL code={}"
                                + " elapsedMs={} exceptionType={} location={}",
                        c.id(),
                        attempt,
                        c.epoch(),
                        error,
                        elapsed,
                        ex.getClass().getName(),
                        InterviewRunWorker.location(ex));
                if (ModelRetryPolicy.retryable(error)
                        && cp.path("outputPhase").asString().equals("ACTION")) {
                    if (store.deferModelFailure(c, attempt, error, elapsed, cp.toString()))
                        return false;
                } else store.attemptFailure(c, attempt, error, elapsed);
                throw new RunFailureException(error, ex);
            }
        }
        boolean requested = reply.finish().equals("tool_calls");
        ((ArrayNode) cp.path("messages")).add(reply.message());
        cp.set("response", reply.message());
        String nextStage =
                requested
                        ? "TOOL"
                        : cp.path("outputPhase").asString().equals("ACTION")
                                ? "ACTION_VALIDATE"
                                : "VALIDATE";
        cp.put("stage", nextStage).put("toolIndex", 0).put("batchReserved", false);
        // 响应与后继阶段一起持久化，恢复到 TOOL / VALIDATE 时直接消费该响应，不重新请求模型。
        store.response(
                c,
                attempt,
                json.writeValueAsString(reply),
                reply.usage().toString(),
                InterviewRunWorker.elapsedMillis(started),
                cp.toString(),
                nextStage);
        // 工具响应被拒绝时仍先保存计费用量和原始响应，再终结运行。
        if (requested && !allowed) throw new RunFailureException("TOOL_DISABLED");
        log.info(
                "run.model_returned runId={} attemptId={} epoch={} phase=MODEL nextPhase={}"
                        + " elapsedMs={}",
                c.id(),
                attempt,
                c.epoch(),
                nextStage,
                InterviewRunWorker.elapsedMillis(started));
        return true;
    }

    void enterFinal(RunContext execution) {
        model.enterFinal(
                execution.checkpoint(),
                execution.session(),
                execution.pending(),
                execution.context());
        store.checkpoint(
                execution.claim(),
                execution.checkpoint().toString(),
                "MODEL",
                "output.finalizing",
                "取证已结束，正在生成本轮结果");
    }
}
