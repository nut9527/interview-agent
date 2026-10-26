package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanningContext;
import cn.edu.whu.zhizhida.interview.api.InterviewPreparation;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewRuleViolation;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import cn.edu.whu.zhizhida.jobs.api.TargetJobSnapshotReader;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 面试应用编排：读取本人资源、固定上下文、申请回答租约、调用模型并条件提交结果。
 * 轮次推进与幂等回放由 InterviewTransitions 决定；模型调用期间不持有数据库事务。
 */
@Service
public class InterviewStateMachine implements InterviewService {

    private static final int MAX_RESUME_SNAPSHOT_LENGTH = 12_000;

    private final InterviewSessionRepository sessionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final InterviewAiService interviewAiService;
    private final ResumeSnapshotReader resumeRepository;
    private final int answerLeaseSeconds;
    private final int agentAnswerLeaseSeconds;
    private final AiRequestQuota aiQuota;
    private final BusinessMetrics metrics;
    private final TargetJobSnapshotReader targetJobs;
    private final InterviewTransitions transitions = new InterviewTransitions();
    private final cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy agentPolicy =
            new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy();

    public InterviewStateMachine(InterviewSessionRepository sessions, CurrentUserProvider users,
            InterviewAiService ai, ResumeSnapshotReader resumes) {
        this(sessions, users, ai, resumes, 30, null, BusinessMetrics.disabled(), null);
    }

    public InterviewStateMachine(InterviewSessionRepository sessions, CurrentUserProvider users,
            InterviewAiService ai, ResumeSnapshotReader resumes, long timeoutSeconds,
            AiRequestQuota quota, BusinessMetrics metrics) {
        this(sessions, users, ai, resumes, timeoutSeconds, quota, metrics, null);
    }

    @Autowired
    public InterviewStateMachine(InterviewSessionRepository sessions, CurrentUserProvider users,
            InterviewAiService ai, ResumeSnapshotReader resumes,
            @Value("${ai.deepseek.timeout-seconds:30}") long timeoutSeconds,
            AiRequestQuota quota, BusinessMetrics metrics, TargetJobSnapshotReader targetJobs) {
        this.sessionRepository = sessions;
        this.currentUserProvider = users;
        this.interviewAiService = ai;
        this.resumeRepository = resumes;
        this.answerLeaseSeconds = Math.toIntExact(Math.addExact(Math.max(1, timeoutSeconds), 15));
        this.agentAnswerLeaseSeconds = Math.toIntExact(Math.addExact(Math.multiplyExact(Math.max(1, timeoutSeconds), 2), 15));
        this.aiQuota = quota;
        this.metrics = metrics;
        this.targetJobs = targetJobs;
    }

    @Transactional(readOnly = true)
    @Override
    public List<InterviewHistoryItem> listHistory() {
        return sessionRepository.findAll(currentUserProvider.requireUserId());
    }

    @Transactional(readOnly = true)
    @Override
    public InterviewSession findSession(String sessionId) {
        return sessionRepository.findById(currentUserProvider.requireUserId(), sessionId)
                .orElseThrow(() -> sessionNotFound(sessionId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public InterviewSession start(InterviewStartRequest request) {
        Long userId = currentUserProvider.requireUserId();
        String targetRole = request.targetRole().trim();
        if ((request.targetJobId() == null) != (request.targetJobRevision() == null)) {
            throw new IllegalArgumentException("请选择目标岗位及其当前版本。");
        }
        if (request.targetJobId() != null && (request.jobDescription() != null && !request.jobDescription().isBlank())) {
            throw new IllegalArgumentException("已保存的目标岗位和自定义 JD 只能选择一种。");
        }
        ResumeProfile resume = request.resumeId() == null ? null : resumeRepository.requireOwned(request.resumeId());
        InterviewPreparation preparation = null;
        String resumeSnapshot = resume == null ? normalizeNullable(request.resumeSummary()) : buildResumeSnapshot(resume);
        String jobSnapshot = resolveJobSnapshot(request, targetRole);
        if (request.targetJobId() != null) {
            var target = targetJobs.find(request.targetJobId());
            if (target.revision() != request.targetJobRevision()) throw new BusinessException(
                    ErrorCode.TARGET_JOB_REVISION_CONFLICT, "目标岗位已更新，请重新选择后开始面试。");
            targetRole = target.title();
            jobSnapshot = "岗位：" + target.title() + "\n公司：" + normalizeNullable(target.company())
                    + "\n目标岗位：" + target.id() + " · 修订 " + target.revision() + "\nJD：" + target.jdText();
        }
        InterviewMode mode = request.mode() == null ? InterviewMode.GENERAL : request.mode();
        int totalRounds;
        try {
            totalRounds = transitions.configuredRounds(request.totalRounds());
        } catch (InterviewRuleViolation ex) {
            throw conflict(ex.getMessage());
        }
        var difficulty = request.difficulty() == null ? cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD : request.difficulty();
        String sessionId = UUID.randomUUID().toString();
        var memory = interviewAiService.supportsAgentDecisions()
                ? agentPolicy.practiceMemory(sessionRepository.recentAgentStates(userId, mode), interviewAiService.executionMode()) : List.<cn.edu.whu.zhizhida.interview.api.InterviewAgentState.PracticeMemory>of();
        var agent = interviewAiService.supportsAgentDecisions()
                ? agentPolicy.plan(mode, totalRounds, planningContext(resume, preparation, jobSnapshot, resumeSnapshot), interviewAiService.executionMode(), memory) : null;
        List<InterviewTurn> turns = new ArrayList<>();
        String startMessage = request.resumeId() == null
                ? "面试开始。本轮由 AI 面试官结合目标岗位和你的回答进行 " + totalRounds + " 轮动态提问。"
                : "面试开始。已载入所选简历，本轮将围绕简历经历和目标岗位进行 " + totalRounds + " 轮动态提问。";
        turns.add(new InterviewTurn("system", startMessage));
        if (aiQuota != null) aiQuota.admit(userId.toString(), AiRequestQuota.INTERVIEW_START_UNITS);
        String context = preparationContext(aiContext(resumeSnapshot, jobSnapshot, mode), preparation);
        if (agent != null) context = InterviewAgentContext.encode(context, agent, agentPolicy.routing(agent, 0, totalRounds), mode == InterviewMode.PROJECT_DEEP_DIVE, difficulty);
        String opening;
        try { opening = interviewAiService.openingQuestion(targetRole, context); }
        catch (InterviewReviewRejected ex) {
            if (!interviewAiService.supportsOpeningRepair()) throw ex;
            final String openingContext = context, openingRole = targetRole;
            opening = metrics.measure(BusinessMetrics.Operation.INTERVIEW_OPENING_REPAIR, () -> {
                if (aiQuota != null) aiQuota.admitInterviewRepair();
                return interviewAiService.repairOpening(openingRole, openingContext, ex.getMessage());
            });
        }
        turns.add(new InterviewTurn("interviewer", opening));

        InterviewSession session = new InterviewSession(
                sessionId,
                targetRole,
                request.resumeId(),
                resumeSnapshot,
                resume == null ? null : resume.revision(),
                null,
                jobSnapshot,
                mode,
                totalRounds,
                null,
                InterviewState.WAITING_ANSWER,
                1,
                List.copyOf(turns), preparation, agent, difficulty
        );
        return sessionRepository.save(userId, session);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public InterviewSession answer(String sessionId, InterviewAnswerRequest request) {
        long started = System.nanoTime();
        try {
            InterviewSession result;
            try {
                result = answerOnce(sessionId, request);
            } catch (InterviewRuleViolation ex) {
                throw conflict(ex.getMessage());
            }
            metrics.record(BusinessMetrics.Operation.INTERVIEW_ANSWER, BusinessMetrics.Outcome.SUCCESS, System.nanoTime() - started);
            return result;
        } catch (RuntimeException ex) {
            metrics.record(BusinessMetrics.Operation.INTERVIEW_ANSWER,
                    ex instanceof BusinessException business && business.status() == HttpStatus.CONFLICT
                            ? BusinessMetrics.Outcome.CONFLICT : BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            throw ex;
        }
    }

    private InterviewSession answerOnce(String sessionId, InterviewAnswerRequest request) {
        Long userId = currentUserProvider.requireUserId();
        InterviewSession session = sessionRepository.findById(userId, sessionId)
                .orElseThrow(() -> sessionNotFound(sessionId));
        String requestId = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
        // 先判断规则与历史回放；已提交的重试不再领取租约、扣减额度或调用模型。
        InterviewTransitions.AnswerDecision decision = transitions.answer(
                session, request.answer(), requestId, request.expectedTurn());
        if (decision instanceof InterviewTransitions.ExistingAnswer existing) return existing.session();
        InterviewTransitions.PendingAnswer pending = (InterviewTransitions.PendingAnswer) decision;
        String leaseToken = UUID.randomUUID().toString();
        int leaseSeconds = session.agent() != null && interviewAiService.supportsReviewRepair() ? agentAnswerLeaseSeconds : answerLeaseSeconds;
        if (!sessionRepository.tryAcquireAnswer(userId, sessionId, session.turnCount(), leaseToken, leaseSeconds)) {
            // 同一请求可能刚刚完成；优先回放已提交结果，避免将成功的重试误报为冲突。
            InterviewSession latest = sessionRepository.findById(userId, sessionId)
                    .orElseThrow(() -> sessionNotFound(sessionId));
            InterviewSession replay = transitions.replay(latest, request.answer(), request.requestId(),
                    request.expectedTurn()).orElse(null);
            if (replay != null) return replay;
            throw conflict("该会话正在处理回答或题目已更新，请稍后重试或重新打开会话。");
        }
        try {
            if (aiQuota != null) aiQuota.admit(userId.toString(), AiRequestQuota.INTERVIEW_REVIEW_UNITS);
            return reviewAndComplete(userId, session, pending, leaseToken);
        } catch (RuntimeException ex) {
            try {
                sessionRepository.releaseAnswer(userId, sessionId, leaseToken);
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure); // 释放失败仍保留原错误；数据库租约会自动过期。
            }
            throw ex;
        }
    }

    private InterviewSession reviewAndComplete(Long userId, InterviewSession session,
                                               InterviewTransitions.PendingAnswer pending, String leaseToken) {
        String context = preparationContext(aiContext(session.resumeSnapshot(), session.jobSnapshot(), session.mode()), session.preparation());
        if (interviewAiService.supportsAgentDecisions()) context = InterviewAgentContext.encode(context, session.agent(),
                session.agent() == null ? null : agentPolicy.routing(session.agent(), pending.answeredCount(), session.totalRounds()), session.mode() == InterviewMode.PROJECT_DEEP_DIVE, session.difficulty());
        InterviewSession updated;
        try {
            updated = validateReview(session, pending, interviewAiService.reviewAnswer(session.targetRole(), context,
                    pending.turns(), pending.answeredCount(), pending.finalRound()));
        } catch (InterviewReviewRejected ex) {
            if (session.agent() == null || !interviewAiService.supportsReviewRepair()) throw ex;
            final String repairContext = context;
            updated = metrics.measure(BusinessMetrics.Operation.INTERVIEW_REPAIR, () -> {
                if (aiQuota != null) aiQuota.admitInterviewRepair();
                return validateReview(session, pending, interviewAiService.repairAnswer(session.targetRole(), repairContext,
                        pending.turns(), pending.answeredCount(), pending.finalRound(), ex.getMessage()));
            });
        }
        // Commit exactly once, only while the original lease and turn are still valid.
        if (!sessionRepository.completeAnswer(userId, updated, session.turnCount(), leaseToken)) {
            throw conflict("回答处理已过期或会话已变更，请重新打开会话确认结果后重试。");
        }
        return updated;
    }

    private InterviewSession validateReview(InterviewSession session, InterviewTransitions.PendingAnswer pending, InterviewAiReply aiReply) {
        InterviewSession updated = pending.complete(aiReply.feedback(), aiReply.nextQuestion(), aiReply.finalSummary());
        if (session.agent() != null) {
            String answer = pending.turns().get(pending.turns().size() - 1).content();
            try {
                updated = updated.withAgent(agentPolicy.advance(session.agent(), pending.answeredCount(),
                        session.totalRounds(), answer, aiReply.agentDecision()));
                if (!pending.finalRound()) agentPolicy.validateQuestion(aiReply.agentDecision().questionCompetencyId(), aiReply.nextQuestion());
            } catch (IllegalArgumentException ex) {
                throw new InterviewReviewRejected(ex.getMessage());
            }
            if (!pending.finalRound() && session.turns().stream().filter(t -> "interviewer".equals(t.speaker()))
                    .anyMatch(t -> questionKey(t.content()).equals(questionKey(aiReply.nextQuestion()))))
                throw new InterviewReviewRejected("nextQuestion 与历史问题重复，请依据当前缺口和可用 probe 重新生成一个不同的问题");
        }
        return updated;
    }

    private String questionKey(String text) {
        return text == null ? "" : text.replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private InterviewPlanningContext planningContext(ResumeProfile resume,
            InterviewPreparation preparation, String job, String summary) {
        var projects = new ArrayList<InterviewPlanningContext.Project>();
        var structured = preparation != null ? List.of(preparation.projectSnapshot())
                : resume == null ? List.<ResumeDetails.Project>of() : resume.details().projects();
        for (var p : structured) projects.add(new InterviewPlanningContext.Project(
                p.name(), p.role(), String.join("；", p.contributions() == null ? List.of() : p.contributions()), String.join("、", p.techStack())));
        if (projects.isEmpty() && resume != null && resume.projectExperiences() != null)
            for (var p : resume.projectExperiences()) projects.add(new InterviewPlanningContext.Project(
                    "项目条目：" + p, "", p, ""));
        return new InterviewPlanningContext(job, projects,
                preparation != null || resume == null || resume.internshipExperiences() == null ? List.of() : resume.internshipExperiences(),
                resume == null || resume.skills() == null ? List.of() : resume.skills(), resume == null ? summary : resume.summary());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.INTERVIEW_STATE_CONFLICT, message);
    }

    @Transactional
    @Override
    public void deleteSession(String sessionId) {
        if (!sessionRepository.deleteById(currentUserProvider.requireUserId(), sessionId)) {
            throw sessionNotFound(sessionId);
        }
    }

    private String resolveJobSnapshot(InterviewStartRequest request, String targetRole) {
        String description = normalizeNullable(request.jobDescription());
        return "岗位：" + targetRole + (description.isBlank() ? "" : "\nJD：" + description);
    }

    private String aiContext(String resumeSnapshot, String jobSnapshot, InterviewMode mode) {
        if (mode == InterviewMode.GENERAL && !jobSnapshot.contains("\n")) return resumeSnapshot;
        String focus = switch (mode) {
            case GENERAL -> "通用岗位能力与经历";
            case BACKEND_BASICS -> "后端基础，考查基础概念及实践";
            case PROJECT_DEEP_DIVE -> "项目深挖，追问项目决策、技术难点和结果";
        };
        return "面试模式：" + focus + "\n目标岗位 JD：" + jobSnapshot + "\n简历：" + resumeSnapshot;
    }

    private String preparationContext(String context, cn.edu.whu.zhizhida.interview.api.InterviewPreparation preparation) {
        if (preparation == null) return context;
        var project = preparation.projectSnapshot();
        String contributions = String.join("；", project.contributions());
        if (contributions.length() > 4000) contributions = contributions.substring(0, 4000)
                + "（本项目贡献过长，模型上下文仅展示前部；完整原文保留在项目快照中。）";
        if (context.length() > 6500) context = context.substring(0, 6500)
                + "（岗位与简历上下文过长，仅展示前部；完整依据以保存的快照为准。）";
        // 将本次所选项目置于上下文前部，通用简历快照可独立截断。
        return "本次练习项目：" + project.name().replace('\n', ' ') + "\n诊断：" + preparation.diagnostic().path("reason").asText()
                + "\n练习主题：" + String.join("；", preparation.topics())
                + "\n本项目当前贡献：" + contributions
                + "\n" + context;
    }


    private String buildResumeSnapshot(ResumeProfile resume) {
        List<String> sections = new ArrayList<>();
        addTextSection(sections, "姓名", resume.ownerName());
        addTextSection(sections, "简历求职方向", resume.targetRole());
        addTextSection(sections, "个人摘要", resume.summary());
        addListSection(sections, "技能", resume.skills());
        addListSection(sections, "教育经历", resume.educationExperiences());
        addListSection(sections, "项目经历", resume.projectEvidenceTexts());
        addListSection(sections, "实习经历", resume.internshipExperiences());
        addListSection(sections, "证书", resume.certificates());
        String snapshot = String.join("\n", sections);
        return snapshot.length() <= MAX_RESUME_SNAPSHOT_LENGTH
                ? snapshot
                : snapshot.substring(0, MAX_RESUME_SNAPSHOT_LENGTH);
    }

    private void addTextSection(List<String> sections, String label, String value) {
        String normalized = normalizeNullable(value);
        if (!normalized.isBlank()) {
            sections.add(label + "：" + normalized);
        }
    }

    private void addListSection(List<String> sections, String label, List<String> values) {
        if (values == null) {
            return;
        }
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (!normalized.isEmpty()) {
            sections.add(label + "：" + String.join("；", normalized));
        }
    }

    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    private ResourceNotFoundException sessionNotFound(String sessionId) {
        return new ResourceNotFoundException("interview session not found: " + sessionId);
    }
    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<InterviewHistoryItem> listHistory(PageRequest page) {
        return sessionRepository.page(currentUserProvider.requireUserId(), page);
    }

}
