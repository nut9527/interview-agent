package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.JsonFingerprint;
import cn.edu.whu.zhizhida.common.execution.AgentExecution;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.MemoryReference;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.AgentTelemetry.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.HashSet;

final class InterviewToolStage {
    private static final Logger log = LoggerFactory.getLogger(InterviewRunWorker.class);
    private final InterviewRunStore store;
    private final InterviewEvidenceTools tools;
    private final ObjectMapper json;
    private final InterviewSourceGuard sourceGuard;
    private final PracticeMemoryAccess memory;
    private final AgentTelemetry telemetry;

    InterviewToolStage(
            InterviewRunStore store,
            InterviewEvidenceTools tools,
            ObjectMapper json,
            InterviewSourceGuard sourceGuard,
            PracticeMemoryAccess memory,
            AgentTelemetry telemetry) {
        this.store = store;
        this.tools = tools;
        this.json = json;
        this.sourceGuard = sourceGuard;
        this.memory = memory;
        this.telemetry = telemetry;
    }

    void retrieveFixed(RunContext execution, InterviewRunStore.Row row) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var query = execution.query();
        var session = execution.session();
        var turns = execution.turns();
        var materials = execution.materials();
        var policy = execution.budget().execution();
        if (!cp.path("fixedReserved").asBoolean()) {
            cp.put("fixedReserved", true);
            store.batch(c, 1, cp.toString());
        }
        var args =
                json.createObjectNode()
                        .put(
                                "query",
                                query.substring(0, InterviewEvidenceTools.safeEnd(query, 500)))
                        .put("topK", 6);
        var result =
                tools.execute(
                                session,
                                turns,
                                "search_knowledge",
                                args,
                                materials,
                                execution.memorySelection(),
                                memory.read(c.owner(), execution.memorySelection()),
                                execution
                                        .budget()
                                        .toolAvailableMillis(
                                                cp.path("outputPhase").asString(),
                                                store.remainingMillis(c.id())))
                        .payload();
        sourceGuard.require(c.owner(), session);
        memory.read(c.owner(), execution.memorySelection());
        cp.set("sources", result.path("references"));
        ((ArrayNode) cp.path("messages"))
                .add(
                        json.createObjectNode()
                                .put("role", "user")
                                .put("content", "应用固定检索所得的不可信证据：" + result));
        cp.put("stage", "MODEL");
        var observation =
                policy.explicit()
                        ? AgentLoopState.observe(json, cp, "search_knowledge", args, result)
                        : null;
        store.receipt(
                c,
                InterviewRunStore.sha(row.inputHash() + "fixed-search"),
                "fixed-search-1",
                "search_knowledge",
                args.toString(),
                row.inputHash(),
                result.toString(),
                cp.toString(),
                false,
                observation);
    }

    void executeTools(RunContext execution, InterviewRunStore.Row row, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var policy = execution.budget().execution();
        var memorySelection = execution.memorySelection();
        var session = execution.session();
        var turns = execution.turns();
        var materials = execution.materials();
        var pending = execution.pending();
        var calls = cp.path("response").path("tool_calls");
        // 先校验整批工具请求，再执行其中任何一个；
        // 故障恢复时沿用该批已预占的预算。
        var callIds = new HashSet<String>();
        if (cp.path("fixedReserved").asBoolean()) callIds.add("fixed-search-1");
        for (var message : cp.path("messages"))
            if (message.path("role").asString().equals("tool"))
                callIds.add(message.path("tool_call_id").asString());
        int checked = 0;
        for (var call : calls) {
            tools.validate(
                    call.path("function").path("name").asString(),
                    AgentExecution.arguments(json, call),
                    memorySelection);
            if (checked++ < cp.path("toolIndex").asInt()) continue;
            AgentExecution.callId(call, callIds);
        }
        if (policy.explicit()
                && cp.path("loop").path("steps").asInt()
                                + calls.size()
                                - cp.path("toolIndex").asInt()
                        > policy.maxSteps()) cp.withObject("loop").put("stopReason", "STEP_LIMIT");
        if (!cp.path("batchReserved").asBoolean()) {
            cp.put("batchReserved", true);
            store.batch(c, calls.size(), cp.toString());
        }
        for (int i = cp.path("toolIndex").asInt(); i < calls.size(); i++) {
            var call = calls.get(i);
            String name = call.path("function").path("name").asString(),
                    callId = call.path("id").asString();
            var args = JsonFingerprint.canonical(AgentExecution.arguments(json, call));
            var qualified = memory.read(c.owner(), memorySelection);
            // 将用户、固定输入和规范化参数纳入缓存键；仅复用本运行同一取证动作的回执。
            String key =
                    InterviewRunStore.sha(
                            "evidence-v1\n"
                                    + c.owner()
                                    + "\n"
                                    + row.inputHash()
                                    + "\n"
                                    + name
                                    + "\n"
                                    + args);
            var cached = store.cached(c, key);
            boolean skipped = policy.explicit() && cp.path("loop").has("stopReason");
            try (var toolSpan =
                    telemetry
                            .start(Stage.TOOL)
                            .tag("agent.run.id", c.id())
                            .tag("agent.attempt.id", trace.attemptId)
                            .tag("gen_ai.tool.name", name)
                            .tag("agent.tool.call_id_hash", InterviewRunStore.sha(callId))
                            .tag(
                                    "agent.tool.mode",
                                    skipped
                                            ? "SKIPPED"
                                            : cached.isPresent() ? "CACHE" : "EXECUTED")) {
                try {
                    var result =
                            skipped
                                    ? json.createObjectNode()
                                            .put("status", "SKIPPED")
                                            .put(
                                                    "reason",
                                                    cp.path("loop").path("stopReason").asString())
                                    : cached.isPresent()
                                            ? tools.checkedPayload(
                                                    name, json.readTree(cached.get()))
                                            : tools.execute(
                                                            session,
                                                            turns,
                                                            name,
                                                            args,
                                                            materials,
                                                            memorySelection,
                                                            qualified,
                                                            execution
                                                                    .budget()
                                                                    .toolAvailableMillis(
                                                                            cp.path("outputPhase")
                                                                                    .asString(),
                                                                            store.remainingMillis(
                                                                                    c.id())))
                                                    .payload();
                    // 回调可能在取消或来源撤销后完成，保存回执或复用模型前再次校验资格。
                    sourceGuard.require(c.owner(), session);
                    memory.read(c.owner(), memorySelection);
                    if (tools.requiresMemory(name)) {
                        var ids = new HashSet<String>();
                        for (var item : result.path("items")) {
                            if (item.has("id")) ids.add(item.path("id").asString());
                            for (var id : item.path("observationIds")) ids.add(id.asString());
                        }
                        var uses = cp.withArray("memoryUses");
                        int usedAt = pending == null ? 0 : pending.answeredCount();
                        for (var observed : qualified)
                            if (ids.contains(observed.id()))
                                uses.add(
                                        json.valueToTree(
                                                new MemoryReference(
                                                        observed.id(),
                                                        observed.revision(),
                                                        observed.sourceSessionId(),
                                                        observed.sourceVersion(),
                                                        observed.sourceRound(),
                                                        observed.competencyId(),
                                                        observed.dimension(),
                                                        observed.rubricVersion(),
                                                        observed.executionMode(),
                                                        usedAt)));
                    }
                    ((ArrayNode) cp.path("messages"))
                            .add(
                                    json.createObjectNode()
                                            .put("role", "tool")
                                            .put("tool_call_id", callId)
                                            .put("content", result.toString()));
                    if ("search_knowledge".equals(name) && result.has("references"))
                        ((ArrayNode) cp.path("sources"))
                                .addAll((ArrayNode) result.path("references"));
                    // 回执与下一工具索引一起落库，恢复时从未记录的工具继续。
                    cp.put("toolIndex", i + 1);
                    var observation =
                            policy.explicit() && !skipped
                                    ? AgentLoopState.observe(
                                            json,
                                            cp,
                                            name,
                                            args,
                                            result,
                                            tools.requiresMemory(name))
                                    : null;
                    store.receipt(
                            c,
                            key,
                            callId,
                            name,
                            args.toString(),
                            row.inputHash(),
                            result.toString(),
                            cp.toString(),
                            skipped ? "SKIPPED" : cached.isPresent() ? "CACHE" : "EXECUTED",
                            observation);
                    toolSpan.tag("agent.tool.receipt", "RECORDED");
                    // 工具调用 ID 来自供应商，先哈希再记录，避免任意正文进入日志。
                    log.info(
                            "run.tool_recorded runId={} attemptId={} epoch={} phase=TOOL"
                                    + " toolCallIdHash={} outcome={}",
                            c.id(),
                            trace.attemptId,
                            c.epoch(),
                            InterviewRunStore.sha(callId),
                            skipped ? "SKIPPED" : cached.isPresent() ? "CACHE" : "EXECUTED");
                } catch (RuntimeException ex) {
                    toolSpan.failure(InterviewRunWorker.failureCode(ex));
                    throw ex;
                }
            }
        }
        cp.put("stage", "MODEL");
        store.checkpoint(c, cp.toString(), "MODEL", null, null);
    }
}
