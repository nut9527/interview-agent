package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentEvaluator;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentReference;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.InterviewExperience;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanningContext;
import cn.edu.whu.zhizhida.interview.api.InterviewPreparation;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.interview.domain.InterviewRuleViolation;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.interview.execution.InterviewEvidenceTools;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.jobs.api.TargetJobSnapshotReader;
import cn.edu.whu.zhizhida.learning.api.ObservationJournal;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 会话查询、输入快照与结果校验；模型调用、配额及租约提交由持久运行 Worker 负责。 */
@Service
public class InterviewStateMachine {

    private static final int MAX_RESUME_SNAPSHOT_LENGTH = 12_000;

    private final InterviewSessionRepository sessionRepository;
    private final boolean adaptiveRoutingEnabled;
    private final ObservationJournal learningJournal;
    private final CurrentUserProvider currentUserProvider;
    private final AiMode aiMode;
    private final ResumeSnapshotReader resumeRepository;
    private final TargetJobSnapshotReader targetJobs;
    private final QuestionCatalog questionCatalog;
    private final KnowledgeCatalog knowledgeCatalog;
    private final InterviewRunStore runStore;
    private final AssessmentEvaluator assessmentEvaluator;
    private final InterviewSourceGuard sourceGuard;
    private final InterviewTransitions transitions = new InterviewTransitions();
    private final InterviewAgentPolicy agentPolicy = new InterviewAgentPolicy();

    public InterviewStateMachine(
            InterviewSessionRepository sessions,
            CurrentUserProvider users,
            AiMode aiMode,
            ResumeSnapshotReader resumes,
            TargetJobSnapshotReader targetJobs,
            QuestionCatalog questionCatalog,
            @Nullable KnowledgeCatalog knowledgeCatalog,
            @Nullable InterviewRunStore runStore,
            @Nullable ObservationJournal learningJournal,
            AssessmentEvaluator assessmentEvaluator,
            InterviewSourceGuard sourceGuard,
            @Value("${interview.agent.adaptive-routing-enabled:true}")
                    boolean adaptiveRoutingEnabled) {
        this.sessionRepository = sessions;
        this.currentUserProvider = users;
        this.aiMode = aiMode;
        this.resumeRepository = resumes;
        this.targetJobs = targetJobs;
        this.questionCatalog = questionCatalog;
        this.knowledgeCatalog = knowledgeCatalog;
        this.runStore = runStore;
        this.learningJournal = learningJournal;
        this.assessmentEvaluator = assessmentEvaluator;
        this.sourceGuard = sourceGuard;
        this.adaptiveRoutingEnabled = adaptiveRoutingEnabled;
    }

    @Transactional(readOnly = true)
    public InterviewSession findSession(String sessionId) {
        Long owner = currentUserProvider.requireUserId();
        var session =
                sessionRepository
                        .findById(owner, sessionId)
                        .orElseThrow(() -> sessionNotFound(sessionId));
        return sourceGuard == null ? session : sourceGuard.view(owner, session);
    }

    /** 仅在认证后受理并归档输入，此阶段不创建可见的半成品会话，也不调用模型。 */
    public InterviewSession prepareStart(InterviewStartRequest request) {
        Long userId = currentUserProvider.requireUserId();
        var knowledgeIds =
                request.knowledgeDocumentIds() == null
                        ? List.<String>of()
                        : request.knowledgeDocumentIds();
        if (!knowledgeIds.isEmpty()
                && (request.trainingTopic() == null
                        || request.questionId() != null
                        || request.retrySessionId() != null))
            throw new UserInputException("知识库资料只能用于新建专项训练；复练沿用原资料快照。");
        if (request.questionVersion() != null && request.questionId() == null)
            throw new UserInputException("题目版本必须与题目 ID 一起提交。");
        if (request.questionId() != null
                && (request.trainingTopic() != null
                        || request.retrySessionId() != null
                        || request.resumeId() != null
                        || request.targetJobId() != null
                        || request.jobDescription() != null
                        || request.resumeSummary() != null))
            throw new UserInputException("选题练习不能同时指定其他训练来源或材料。");
        if ((request.retrySessionId() == null) != (request.retryRound() == null))
            throw new UserInputException("复练需要原会话和原回答轮次。");
        if (request.retrySessionId() != null
                && (request.trainingTopic() != null
                        || request.resumeId() != null
                        || request.targetJobId() != null
                        || request.jobDescription() != null
                        || request.resumeSummary() != null))
            throw new UserInputException("复练沿用原场材料，请勿同时指定新材料或主题。");
        boolean trainingRequested =
                request.trainingTopic() != null
                        || request.retrySessionId() != null
                        || request.questionId() != null;
        if (trainingRequested
                && (request.totalRounds() == null
                        || request.totalRounds() < 3
                        || request.totalRounds() > 6))
            throw new UserInputException("专项训练和薄弱点复练仅支持 3 至 6 轮。");
        InterviewSession source =
                request.retrySessionId() == null
                        ? null
                        : sessionRepository
                                .findById(userId, request.retrySessionId())
                                .orElseThrow(() -> sessionNotFound(request.retrySessionId()));
        InterviewTraining.Retry retry =
                source == null ? null : retrySnapshot(source, request.retryRound());
        var question =
                request.questionId() == null
                        ? null
                        : questionCatalog.requirePublished(
                                request.questionId(), request.questionVersion());
        if (source != null
                && source.agent().training() != null
                && source.agent().training().question() != null) {
            question = source.agent().training().question();
            questionCatalog.requirePublished(question.id(), null);
        }
        InterviewTraining training =
                trainingRequested
                        ? new InterviewTraining(request.trainingTopic(), retry, question)
                        : null;
        var knowledge =
                source != null && source.agent().training() != null
                        ? source.agent().training().knowledge()
                        : knowledgeIds.isEmpty()
                                ? null
                                : knowledgeCatalog.snapshot(
                                        knowledgeIds,
                                        switch (request.trainingTopic()) {
                                            case DATABASE -> "数据库 事务 索引 缓存";
                                            case CONCURRENCY -> "并发 线程 锁";
                                            case PERFORMANCE -> "性能 延迟 吞吐";
                                            case PROJECT -> "项目 设计 验证";
                                        });
        if (training != null && knowledge != null)
            training = new InterviewTraining(training.topic(), retry, question, knowledge);
        String targetRole = request.targetRole().trim();
        if (question != null) targetRole = question.title();
        if ((request.targetJobId() == null) != (request.targetJobRevision() == null)) {
            throw new UserInputException("请选择目标岗位及其当前版本。");
        }
        if (request.targetJobId() != null
                && (request.jobDescription() != null && !request.jobDescription().isBlank())) {
            throw new UserInputException("已保存的目标岗位和自定义 JD 只能选择一种。");
        }
        ResumeProfile resume =
                request.resumeId() == null
                        ? null
                        : resumeRepository.requireOwned(request.resumeId());
        InterviewPreparation preparation = null;
        String resumeSnapshot =
                resume == null
                        ? normalizeNullable(request.resumeSummary())
                        : buildResumeSnapshot(resume);
        String jobSnapshot = resolveJobSnapshot(request, targetRole);
        if (source != null) {
            targetRole = source.targetRole();
            resumeSnapshot = source.resumeSnapshot();
            jobSnapshot = source.jobSnapshot();
        }
        if (request.targetJobId() != null) {
            var target = targetJobs.find(request.targetJobId());
            if (target.revision() != request.targetJobRevision())
                throw new BusinessException(
                        ErrorCode.TARGET_JOB_REVISION_CONFLICT, "目标岗位已更新，请重新选择后开始面试。");
            targetRole = target.title();
            jobSnapshot =
                    "岗位："
                            + target.title()
                            + "\n公司："
                            + normalizeNullable(target.company())
                            + "\n目标岗位："
                            + target.id()
                            + " · 修订 "
                            + target.revision()
                            + "\nJD："
                            + target.jdText();
        }
        InterviewMode mode = request.mode() == null ? InterviewMode.GENERAL : request.mode();
        if (source != null) mode = source.mode();
        else if (question != null) mode = InterviewMode.BACKEND_BASICS;
        else if (request.trainingTopic() != null)
            mode =
                    request.trainingTopic() == InterviewTraining.Topic.PROJECT
                            ? InterviewMode.PROJECT_DEEP_DIVE
                            : InterviewMode.BACKEND_BASICS;
        int totalRounds;
        try {
            totalRounds = transitions.configuredRounds(request.totalRounds());
        } catch (InterviewRuleViolation ex) {
            throw conflict(ex.getMessage());
        }
        var difficulty =
                request.difficulty() == null ? InterviewDifficulty.STANDARD : request.difficulty();
        if (source != null) difficulty = source.difficulty();
        String sessionId = UUID.randomUUID().toString();
        var memory = List.<InterviewAgentState.PracticeMemory>of();
        var agent =
                agentPolicy.plan(
                        mode,
                        totalRounds,
                        planningContext(resume, preparation, jobSnapshot, resumeSnapshot),
                        aiMode.name().toLowerCase(Locale.ROOT),
                        memory);
        if (training != null)
            agent =
                    agentPolicy.trainingPlan(
                            training,
                            planningContext(resume, preparation, jobSnapshot, resumeSnapshot),
                            aiMode.name().toLowerCase(Locale.ROOT),
                            memory,
                            source == null
                                    ? null
                                    : source.agent().plan().stream()
                                            .filter(c -> c.id().equals(retry.competencyId()))
                                            .findFirst()
                                            .orElseThrow());
        if (adaptiveRoutingEnabled
                && request.experienceMode() != InterviewExperience.Format.FORMAL)
            agent = agentPolicy.adaptivePlan(agent);
        List<InterviewTurn> turns = new ArrayList<>();
        String startMessage =
                request.resumeId() == null
                        ? "面试开始。本轮由 AI 面试官结合目标岗位和你的回答进行 " + totalRounds + " 轮动态提问。"
                        : "面试开始。已载入所选简历，本轮将围绕简历经历和目标岗位进行 " + totalRounds + " 轮动态提问。";
        turns.add(new InterviewTurn("system", startMessage));
        if (training != null)
            turns.set(
                    0,
                    new InterviewTurn(
                            "system",
                            retry == null
                                    ? "专项训练开始，共 " + totalRounds + " 轮。问题将围绕所选主题，根据回答继续追问。"
                                    : "薄弱点复练开始："
                                            + retry.competencyName()
                                            + "，共 "
                                            + totalRounds
                                            + " 轮。沿用原场材料与难度，重点核对原回答缺口。"));
        InterviewSession session =
                new InterviewSession(
                        sessionId,
                        targetRole,
                        source == null ? request.resumeId() : source.resumeId(),
                        resumeSnapshot,
                        source != null
                                ? source.resumeRevision()
                                : resume == null ? null : resume.revision(),
                        null,
                        jobSnapshot,
                        mode,
                        totalRounds,
                        null,
                        InterviewState.WAITING_ANSWER,
                        1,
                        List.copyOf(turns),
                        preparation,
                        agent,
                        difficulty);
        session =
                session.withExperience(
                        new InterviewExperience(
                                request.experienceMode(),
                                request.timeLimitMinutes(),
                                Instant.now()));
        if (sourceGuard != null) sourceGuard.require(userId, session);
        return session;
    }

    private InterviewTraining.Retry retrySnapshot(InterviewSession source, int round) {
        if (source.state() != InterviewState.FINISHED || source.agent() == null)
            throw new UserInputException("请从已完成会话的能力复盘发起复练。");
        var evaluation =
                source.agent().evaluations().stream()
                        .filter(e -> e.round() == round)
                        .findFirst()
                        .orElseThrow(() -> new UserInputException("原回答轮次不存在。"));
        var competency =
                source.agent().plan().stream()
                        .filter(c -> c.id().equals(evaluation.competencyId()))
                        .findFirst()
                        .orElseThrow();
        String question = "";
        int answered = 0;
        for (var turn : source.turns()) {
            if ("interviewer".equals(turn.speaker())) question = turn.content();
            if ("user".equals(turn.speaker()) && ++answered == round)
                return new InterviewTraining.Retry(
                        source.sessionId(),
                        round,
                        competency.id(),
                        competency.name(),
                        question,
                        turn.content(),
                        evaluation.decision().level(),
                        evaluation.decision().evidence(),
                        evaluation.decision().gap());
        }
        throw new UserInputException("原回答记录不完整。");
    }

    @Transactional(readOnly = true)
    public PageResult<InterviewHistoryItem> listRetries(String sessionId, PageRequest page) {
        findSession(sessionId);
        return sessionRepository.retries(currentUserProvider.requireUserId(), sessionId, page);
    }

    public InterviewSession validateReview(
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            InterviewAiReply aiReply) {
        return validateReview(currentUserProvider.requireUserId(), session, pending, aiReply);
    }

    public InterviewSession validateReview(
            Long owner,
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            InterviewAiReply aiReply) {
        return validateReview(owner, session, pending, aiReply, null);
    }

    InterviewSession validateReview(
            Long owner,
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            InterviewAiReply aiReply,
            List<AssessmentReference> references) {
        if (session.agent() != null
                && InterviewAgentPolicy.adaptive(session.agent())
                && session.experience() != null
                && session.experience().format() == InterviewExperience.Format.FORMAL)
            throw new IllegalStateException("Formal interview cannot use adaptive routing");
        InterviewSession updated =
                pending.complete(
                        aiReply.feedback(), aiReply.nextQuestion(), aiReply.finalSummary());
        if (session.agent() == null) return updated;

        String answer = pending.turns().getLast().content();
        try {
            var advanced =
                    agentPolicy.advance(
                            session.agent(),
                            pending.answeredCount(),
                            session.totalRounds(),
                            answer,
                            aiReply.agentDecision(), session.sessionId(), cn.edu.whu.zhizhida.common.Sha256.hex(answer), session.difficulty(), aiReply.nextQuestion());
            var assessed =
                    assessAnswer(owner, session, pending, aiReply, answer, references, advanced);
            updated = updated.withAgent(assessed);
            if (!pending.finalRound())
                agentPolicy.validateQuestion(
                        updated.agent().evaluations().getLast().decision().questionCompetencyId(),
                        updated.turns().getLast().content());
        } catch (IllegalArgumentException ex) {
            throw new InterviewReviewRejected(ex.getMessage());
        }
        rejectRepeatedQuestion(session, pending, updated);
        return updated;
    }

    private InterviewAgentState assessAnswer(
            Long owner,
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            InterviewAiReply aiReply,
            String answer,
            List<AssessmentReference> references,
            InterviewAgentState advanced) {
        // 后台执行者显式传入受理时的用户身份，不读取请求线程的本地身份。
        if (aiReply.assessment() != null && (assessmentEvaluator == null || owner == null))
            throw new IllegalStateException(
                    "Assessment validation requires a trusted owner and evaluator");
        if (assessmentEvaluator == null || owner == null) return advanced;
        var assessed =
                assessmentEvaluator.evaluateAnswer(
                        owner,
                        session.sessionId() + ":answer:" + pending.answeredCount(),
                        answer,
                        Instant.now(),
                        aiReply.assessment(),
                        session.agent().executionMode(),
                        references == null
                                ? InterviewAssessmentSources.references(
                                        owner, session.agent().training())
                                : references);
        return advanced.withAssessment(pending.answeredCount(), assessed);
    }

    private void rejectRepeatedQuestion(
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            InterviewSession updated) {
        if (pending.finalRound()) return;
        String selectedQuestion = questionKey(updated.turns().getLast().content());
        if (session.turns().stream()
                .filter(t -> "interviewer".equals(t.speaker()))
                .anyMatch(t -> questionKey(t.content()).equals(selectedQuestion)))
            throw new InterviewReviewRejected("nextQuestion 与历史问题重复，请依据当前缺口和可用 probe 重新生成一个不同的问题");
    }

    /** 新命令启动时单独保留各结构化项目，不受旧版 12k 简历概览截断的影响。 */
    public List<InterviewEvidenceTools.Section> runProjectSections(InterviewStartRequest request) {
        if (request.resumeId() == null) return List.of();
        var resume = resumeRepository.requireOwned(request.resumeId());
        var result = new ArrayList<InterviewEvidenceTools.Section>();
        int i = 0;
        for (var project : resume.details().projects().stream().limit(10).toList()) {
            String text =
                    "项目："
                            + project.name()
                            + "\n职责："
                            + project.role()
                            + "\n技术："
                            + String.join("、", project.techStack())
                            + "\n贡献："
                            + String.join("；", project.contributions());
            boolean truncated = text.length() > 10000;
            text = text.substring(0, Math.min(text.length(), 10000));
            InterviewEvidenceTools.material(
                    result, "project-" + (++i), text, Long.toString(resume.revision()), truncated);
        }
        return List.copyOf(result);
    }

    public String fixedOpening(InterviewSession s) {
        return s.agent() != null
                        && s.agent().training() != null
                        && s.agent().training().question() != null
                        && s.agent().training().objective() == null
                ? s.agent().training().question().question()
                : null;
    }

    public InterviewSession opened(InterviewSession fixed, String question) {
        var turns = new ArrayList<>(fixed.turns());
        turns.add(new InterviewTurn("interviewer", question));
        return new InterviewSession(
                fixed.sessionId(),
                fixed.targetRole(),
                fixed.resumeId(),
                fixed.resumeSnapshot(),
                fixed.resumeRevision(),
                fixed.jobId(),
                fixed.jobSnapshot(),
                fixed.mode(),
                fixed.totalRounds(),
                null,
                InterviewState.WAITING_ANSWER,
                1,
                List.copyOf(turns),
                fixed.preparation(),
                fixed.agent(),
                fixed.difficulty(),
                fixed.experience());
    }

    /** 将会话的材料文本与本轮 Agent 状态组装为 AI 上下文，不回写 resumeSnapshot。 */
    public String runContext(
            InterviewSession s,
            InterviewTransitions.PendingAnswer pending,
            List<KnowledgeSnapshot.Reference> sources) {
        String context =
                preparationContext(
                        aiContext(s.resumeSnapshot(), s.jobSnapshot(), s.mode()), s.preparation());
        return s.agent() == null
                ? context
                : InterviewAgentContext.encode(
                        context,
                        s.agent(),
                        agentPolicy.routing(
                                s.agent(),
                                pending == null ? 0 : pending.answeredCount(),
                                s.totalRounds()),
                        s.mode() == InterviewMode.PROJECT_DEEP_DIVE,
                        s.difficulty(),
                        sources);
    }

    private String questionKey(String text) {
        return text == null
                ? ""
                : text.replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    private InterviewPlanningContext planningContext(
            ResumeProfile resume, InterviewPreparation preparation, String job, String summary) {
        var projects = new ArrayList<InterviewPlanningContext.Project>();
        var structured =
                preparation != null
                        ? List.of(preparation.projectSnapshot())
                        : resume == null
                                ? List.<ResumeDetails.Project>of()
                                : resume.details().projects();
        for (var p : structured)
            projects.add(
                    new InterviewPlanningContext.Project(
                            p.name(),
                            p.role(),
                            String.join(
                                    "；", p.contributions() == null ? List.of() : p.contributions()),
                            String.join("、", p.techStack())));
        if (projects.isEmpty() && resume != null && resume.projectExperiences() != null)
            for (var p : resume.projectExperiences())
                projects.add(new InterviewPlanningContext.Project("项目条目：" + p, "", p, ""));
        return new InterviewPlanningContext(
                job,
                projects,
                preparation != null || resume == null || resume.internshipExperiences() == null
                        ? List.of()
                        : resume.internshipExperiences(),
                resume == null || resume.skills() == null ? List.of() : resume.skills(),
                resume == null ? summary : resume.summary());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.INTERVIEW_STATE_CONFLICT, message);
    }

    @Transactional
    public void deleteSession(String sessionId) {
        Long owner = currentUserProvider.requireUserId();
        if (runStore != null) {
            sessionRepository
                    .findByIdForUpdate(owner, sessionId)
                    .orElseThrow(() -> sessionNotFound(sessionId));
            runStore.deleteSession(owner, sessionId);
        }
        if (!sessionRepository.deleteById(owner, sessionId)) {
            throw sessionNotFound(sessionId);
        }
    }

    private String resolveJobSnapshot(InterviewStartRequest request, String targetRole) {
        String description = normalizeNullable(request.jobDescription());
        return "岗位：" + targetRole + (description.isBlank() ? "" : "\nJD：" + description);
    }

    private String aiContext(String resumeSnapshot, String jobSnapshot, InterviewMode mode) {
        if (mode == InterviewMode.GENERAL && !jobSnapshot.contains("\n")) return resumeSnapshot;
        String focus =
                switch (mode) {
                    case GENERAL -> "通用岗位能力与经历";
                    case BACKEND_BASICS -> "后端基础，考查基础概念及实践";
                    case PROJECT_DEEP_DIVE -> "项目深挖，追问项目决策、技术难点和结果";
                };
        return "面试模式：" + focus + "\n目标岗位 JD：" + jobSnapshot + "\n简历：" + resumeSnapshot;
    }

    private String preparationContext(String context, InterviewPreparation preparation) {
        if (preparation == null) return context;
        var project = preparation.projectSnapshot();
        String contributions = String.join("；", project.contributions());
        if (contributions.length() > 4000)
            contributions = contributions.substring(0, 4000) + "（本项目贡献过长，模型上下文仅展示前部；完整原文保留在项目快照中。）";
        if (context.length() > 6500)
            context = context.substring(0, 6500) + "（岗位与简历上下文过长，仅展示前部；完整依据以保存的快照为准。）";
        // 将本次所选项目置于上下文前部，通用简历快照可独立截断。
        return "本次练习项目："
                + project.name().replace('\n', ' ')
                + "\n诊断："
                + preparation.diagnostic().path("reason").asText()
                + "\n练习主题："
                + String.join("；", preparation.topics())
                + "\n本项目当前贡献："
                + contributions
                + "\n"
                + context;
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
        List<String> normalized =
                values.stream()
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<InterviewHistoryItem> listHistory(PageRequest page) {
        return sessionRepository.page(currentUserProvider.requireUserId(), page);
    }
}
