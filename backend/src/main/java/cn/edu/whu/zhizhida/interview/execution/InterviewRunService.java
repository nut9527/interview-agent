package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.JsonFingerprint;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewExperience;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewRun.InterviewRunTrace;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.quota.RedisSubmissionRateLimiter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/** 面向当前登录用户受理持久运行命令，固定输入与配置，并提供归属受限的查询和取消入口。 */
@Service
public class InterviewRunService {
    private final CurrentUserProvider users;
    private final InterviewRunStore store;
    private final InterviewStateMachine machine;
    private final InterviewSessionRepository sessions;
    private final InterviewRunModel model;
    private final ObjectMapper json;
    private final String route;
    private final InterviewSourceGuard sourceGuard;
    private final PracticeMemoryAccess memory;
    private final ObjectProvider<RedisSubmissionRateLimiter> limiters;

    public InterviewRunService(
            CurrentUserProvider users,
            InterviewRunStore store,
            InterviewStateMachine machine,
            InterviewSessionRepository sessions,
            InterviewRunModel model,
            ObjectMapper json,
            @Value("${interview.runs.route:TOOL}") String route,
            InterviewSourceGuard sourceGuard,
            PracticeMemoryAccess memory,
            ObjectProvider<RedisSubmissionRateLimiter> limiters) {
        this.users = users;
        this.store = store;
        this.machine = machine;
        this.sessions = sessions;
        this.model = model;
        this.json = json;
        this.sourceGuard = sourceGuard;
        this.memory = memory;
        this.limiters = limiters;
        if (!Set.of("LEGACY", "FIXED", "TOOL").contains(route))
            throw new IllegalArgumentException("Invalid interview run route");
        this.route = route;
    }

    public InterviewRun start(InterviewRun.Start request) {
        admitStart(request);
        return acceptStart(request);
    }

    public void admitStart(InterviewRun.Start request) {
        admitStart(request, null);
    }

    public void admitStart(InterviewRun.Start request, String objective) {
        admit(users.requireUserId(), "START", request.requestId(), startHash(request, objective));
    }

    /** 训练任务先在事务外调用 admitStart，再在绑定任务的事务内受理。 */
    public InterviewRun acceptStart(InterviewRun.Start request) {
        return acceptStart(request, null);
    }

    public InterviewRun acceptStart(InterviewRun.Start request, String objective) {
        long owner = users.requireUserId();
        return store.accept(
                        owner,
                        "START",
                        request.requestId(),
                        startHash(request, objective),
                        null,
                        0,
                        () -> {
                            var fixed = machine.prepareStart(request.configuration());
                            if (objective != null) fixed = fixed.withAgent(fixed.agent().withTraining(
                                    fixed.agent().training().withObjective(objective)));
                            var input = json.createObjectNode();
                            input.set("session", json.valueToTree(fixed));
                            var materials =
                                    request.configuration().retrySessionId() == null
                                            ? json.valueToTree(
                                                    machine.runProjectSections(
                                                            request.configuration()))
                                            : json.readTree(
                                                    store.materials(
                                                            owner,
                                                            request.configuration()
                                                                    .retrySessionId()));
                            input.set("materials", materials);
                            freezeMemory(owner, fixed, input);
                            return new InterviewRunStore.Fixed(
                                    fixed.sessionId(),
                                    input.toString(),
                                    model.configuration(),
                                    route);
                        })
                .view();
    }

    public InterviewRun answer(String sessionId, InterviewRun.Answer request) {
        long owner = users.requireUserId();
        String intent = hash(List.of(sessionId, request.expectedTurn(), request.answer()));
        admit(owner, "ANSWER", request.requestId(), intent);
        return store.accept(
                        owner,
                        "ANSWER",
                        request.requestId(),
                        intent,
                        sessionId,
                        request.expectedTurn(),
                        () -> {
                            var fixed =
                                    sessions.findById(owner, sessionId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResourceNotFoundException(
                                                                    "interview session not found"));
                            sourceGuard.require(owner, fixed);
                            var input = json.createObjectNode();
                            input.set("answer", json.valueToTree(request));
                            input.put("sessionHash", hash(fixed));
                            input.put(
                                    "materialsHash",
                                    InterviewRunStore.sha(store.materials(owner, sessionId)));
                            freezeMemory(owner, fixed, input);
                            return new InterviewRunStore.Fixed(
                                    sessionId, input.toString(), model.configuration(), route);
                        })
                .view();
    }

    private void admit(long owner, String operation, String key, String hash) {
        var limiter = limiters.getIfAvailable();
        if (limiter == null) return;
        var existing = store.command(owner, operation, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash))
                throw new BusinessException(ErrorCode.CONFLICT, "面试命令或回合已变更，请核对原运行结果。");
            return;
        }
        limiter.require(owner);
    }

    /** 固定本命令可引用的历史观察集合，后续执行仍需检查这些观察的当前授权。 */
    private void freezeMemory(long owner, InterviewSession session, ObjectNode input) {
        var agent = session.agent();
        var selected =
                agent == null || !route.equals("TOOL")
                        ? PracticeMemoryAccess.Selection.none()
                        : memory.freeze(
                                owner,
                                agent.plan().stream().map(c -> c.id()).toList(),
                                agent.executionMode(),
                                session.sessionId());
        input.set("practiceMemory", json.valueToTree(selected));
    }

    public InterviewRun get(String id) {
        return store.get(users.requireUserId(), id).view();
    }

    public InterviewRun byCommand(String operation, String requestId) {
        return store.command(users.requireUserId(), operation, requestId)
                .orElseThrow(() -> new ResourceNotFoundException("interview run not found"))
                .view();
    }

    public List<InterviewRun> active() {
        return store.active(users.requireUserId()).stream()
                .map(InterviewRunStore.Row::view)
                .toList();
    }

    public List<InterviewRun> forSession(String id) {
        long owner = users.requireUserId();
        sessions.findById(owner, id)
                .orElseThrow(() -> new ResourceNotFoundException("interview session not found"));
        return store.forSession(owner, id).stream().map(InterviewRunStore.Row::view).toList();
    }

    public InterviewRun cancel(String id) {
        return store.cancel(users.requireUserId(), id).view();
    }

    public InterviewRunTrace trace(String id) {
        long owner = users.requireUserId();
        var row = store.get(owner, id);
        var session = sessions.findById(owner, row.sessionId()).orElse(null);
        if (session == null && row.operation().equals("START")) {
            var archived = json.readTree(row.input()).path("session");
            if (archived.isObject()) session = json.treeToValue(archived, InterviewSession.class);
        }
        // 正式面试结束前隐藏取证轨迹，避免通过运行详情提前暴露参考信息。
        boolean hidden =
                session == null
                        || session.experience() != null
                                && session.experience().format()
                                        == InterviewExperience.Format.FORMAL
                                && session.state() != InterviewState.FINISHED;
        return store.trace(owner, id, hidden);
    }

    public List<InterviewRun.Event> events(String id, long after) {
        return store.events(users.requireUserId(), id, after);
    }

    public long owner(String id) {
        long owner = users.requireUserId();
        store.get(owner, id);
        return owner;
    }

    private String startHash(InterviewRun.Start request, String objective) {
        return hash(objective == null ? request.configuration() : List.of(request.configuration(), objective));
    }

    private String hash(Object value) {
        return JsonFingerprint.sha(json.valueToTree(value));
    }

}
