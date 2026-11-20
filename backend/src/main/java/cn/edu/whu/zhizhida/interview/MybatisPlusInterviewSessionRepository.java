package cn.edu.whu.zhizhida.interview;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewPreparation;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionEntity;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionMapper;
import cn.edu.whu.zhizhida.learning.api.ObservationJournal;
import tools.jackson.core.type.TypeReference;

/** 面试会话的持久化适配器，以用户归属、题号和租约令牌共同约束回答写入。 租约是否过期由数据库时间判断，使不同应用实例使用同一到期标准。 */
@Repository
public class MybatisPlusInterviewSessionRepository implements InterviewSessionRepository {

        private static final TypeReference<List<InterviewTurn>> TURN_LIST = new TypeReference<>() {
        };

        private final InterviewSessionMapper interviewSessionMapper;
        private final JsonColumnCodec jsonCodec;
        private final InterviewSourceGuard sourceGuard;
        private final Optional<ObservationJournal> observationJournal;

        private void recordCompleted(Long owner, InterviewSession session, String agentJson) {
                if (session.state() == InterviewState.FINISHED && agentJson != null)
                        observationJournal.ifPresent(
                                        journal -> journal.record(
                                                        owner,
                                                        session.sessionId(),
                                                        InterviewPracticeEvidenceSource.version(agentJson)));
        }

        private void authorize(Long owner, InterviewSession session) {
                sourceGuard.lock(owner, session);
        }

        public MybatisPlusInterviewSessionRepository(
                        InterviewSessionMapper interviewSessionMapper,
                        JsonColumnCodec jsonCodec,
                        InterviewSourceGuard sourceGuard,
                        Optional<ObservationJournal> observationJournal) {
                this.interviewSessionMapper = interviewSessionMapper;
                this.jsonCodec = jsonCodec;
                this.sourceGuard = sourceGuard;
                this.observationJournal = observationJournal;
        }

        @Override
        @org.springframework.transaction.annotation.Transactional
        public InterviewSession save(Long userId, InterviewSession session) {
                authorize(userId, session);
                InterviewSessionEntity entity = toEntity(userId, session);
                InterviewSessionEntity existing = interviewSessionMapper.selectById(session.sessionId());
                if (existing == null) {
                        entity.setCreatedAt(LocalDateTime.now());
                        entity.setUpdatedAt(entity.getCreatedAt());
                        interviewSessionMapper.insert(entity);
                } else {
                        entity.setUpdatedAt(LocalDateTime.now());
                        int changed = interviewSessionMapper.update(
                                        entity,
                                        Wrappers.<InterviewSessionEntity>lambdaUpdate()
                                                        .eq(InterviewSessionEntity::getSessionId, session.sessionId())
                                                        .eq(InterviewSessionEntity::getUserId, userId));
                        if (changed != 1)
                                throw new IllegalStateException("Session ownership changed");
                }
                recordCompleted(userId, session, entity.getAgentJson());
                return session;
        }

        @Override
        public PageResult<InterviewHistoryItem> retries(
                        Long userId, String sessionId, PageRequest page) {
                var filter = Wrappers.<InterviewSessionEntity>lambdaQuery()
                                .eq(InterviewSessionEntity::getUserId, userId)
                                .apply(
                                                "JSON_UNQUOTE(JSON_EXTRACT(agent_json,"
                                                                + " '$.training.retry.sourceSessionId')) = {0}",
                                                sessionId);
                return historyPage(filter, page);
        }

        @Override
        public PageResult<InterviewHistoryItem> page(Long userId, PageRequest page) {
                return historyPage(
                                Wrappers.<InterviewSessionEntity>lambdaQuery()
                                                .eq(InterviewSessionEntity::getUserId, userId),
                                page);
        }

        private PageResult<InterviewHistoryItem> historyPage(
                        LambdaQueryWrapper<InterviewSessionEntity> filter, PageRequest page) {
                long total = interviewSessionMapper.selectCount(filter);
                var items = interviewSessionMapper
                                .selectList(
                                                filter.select(
                                                                InterviewSessionEntity::getSessionId,
                                                                InterviewSessionEntity::getTargetRole,
                                                                InterviewSessionEntity::getResumeId,
                                                                InterviewSessionEntity::getMode,
                                                                InterviewSessionEntity::getTotalRounds,
                                                                InterviewSessionEntity::getState,
                                                                InterviewSessionEntity::getTurnCount,
                                                                InterviewSessionEntity::getAnsweredCount,
                                                                InterviewSessionEntity::getPreview,
                                                                InterviewSessionEntity::getCreatedAt,
                                                                InterviewSessionEntity::getUpdatedAt)
                                                                .orderByDesc(
                                                                                InterviewSessionEntity::getUpdatedAt,
                                                                                InterviewSessionEntity::getSessionId)
                                                                .last(page.limitSql()))
                                .stream()
                                .map(this::toHistoryItem)
                                .toList();
                return new PageResult<>(items, total, page);
        }

        @Override
        public Optional<InterviewSession> findById(Long userId, String sessionId) {
                return findEntity(userId, sessionId, false).map(this::toSession);
        }

        @Override
        public Optional<InterviewSession> findByIdForUpdate(Long userId, String sessionId) {
                return findEntity(userId, sessionId, true).map(this::toSession);
        }

        @Override
        @org.springframework.transaction.annotation.Transactional
        public boolean deleteById(Long userId, String sessionId) {
                if (findEntity(userId, sessionId, true).isEmpty())
                        return false;
                observationJournal.ifPresent(journal -> journal.sourceRemoved(userId, sessionId));
                return interviewSessionMapper.delete(
                                Wrappers.<InterviewSessionEntity>lambdaQuery()
                                                .eq(InterviewSessionEntity::getSessionId, sessionId)
                                                .eq(InterviewSessionEntity::getUserId, userId)) > 0;
        }

        private Optional<InterviewSessionEntity> findEntity(
                        Long userId, String sessionId, boolean forUpdate) {
                InterviewSessionEntity entity = interviewSessionMapper.selectOne(
                                Wrappers.<InterviewSessionEntity>lambdaQuery()
                                                .eq(InterviewSessionEntity::getSessionId, sessionId)
                                                .eq(InterviewSessionEntity::getUserId, userId)
                                                .last(forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
                return Optional.ofNullable(entity);
        }

        @Override
        public int deleteAllByUserId(Long userId) {
                return interviewSessionMapper.delete(
                                Wrappers.<InterviewSessionEntity>lambdaQuery()
                                                .eq(InterviewSessionEntity::getUserId, userId));
        }

        private InterviewSessionEntity toEntity(Long userId, InterviewSession session) {
                InterviewSessionEntity entity = new InterviewSessionEntity();
                entity.setSessionId(session.sessionId());
                entity.setUserId(userId);
                entity.setTargetRole(session.targetRole());
                entity.setResumeId(session.resumeId());
                entity.setResumeSnapshot(session.resumeSnapshot());
                entity.setResumeRevision(session.resumeRevision());
                entity.setJobId(session.jobId());
                entity.setJobSnapshot(session.jobSnapshot());
                entity.setMode(session.mode().name());
                entity.setDifficulty(session.difficulty().name());
                entity.setExperienceJson(
                                session.experience() == null ? null : jsonCodec.write(session.experience()));
                entity.setTotalRounds(session.totalRounds());
                entity.setState(session.state().name());
                entity.setTurnCount(session.turnCount());
                entity.setTurnsJson(jsonCodec.write(session.turns()));
                entity.setPreparationJson(
                                session.preparation() == null ? null : jsonCodec.write(session.preparation()));
                entity.setAgentJson(session.agent() == null ? null : jsonCodec.write(session.agent()));
                entity.setAnsweredCount(
                                (int) session.turns().stream().filter(t -> "user".equals(t.speaker())).count());
                entity.setPreview(
                                session.turns().stream()
                                                .filter(t -> "interviewer".equals(t.speaker()))
                                                .reduce((a, b) -> b)
                                                .map(InterviewTurn::content)
                                                .map(this::truncatePreview)
                                                .orElse("暂无面试内容"));
                return entity;
        }

        private InterviewSession toSession(InterviewSessionEntity entity) {
                return new InterviewSession(
                                entity.getSessionId(),
                                entity.getTargetRole(),
                                entity.getResumeId(),
                                entity.getResumeSnapshot() == null ? "" : entity.getResumeSnapshot(),
                                entity.getResumeRevision(),
                                entity.getJobId(),
                                entity.getJobSnapshot() == null ? "" : entity.getJobSnapshot(),
                                entity.getMode() == null
                                                ? InterviewMode.GENERAL
                                                : InterviewMode.valueOf(entity.getMode()),
                                entity.getTotalRounds() == null ? 3 : entity.getTotalRounds(),
                                toInstant(entity.getAnswerLeaseUntil()),
                                InterviewState.valueOf(entity.getState()),
                                entity.getTurnCount(),
                                jsonCodec.read(entity.getTurnsJson(), TURN_LIST),
                                entity.getPreparationJson() == null
                                                ? null
                                                : jsonCodec.read(entity.getPreparationJson(),
                                                                InterviewPreparation.class),
                                entity.getAgentJson() == null
                                                ? null
                                                : jsonCodec.read(
                                                                entity.getAgentJson(),
                                                                cn.edu.whu.zhizhida.interview.api.InterviewAgentState.class),
                                entity.getDifficulty() == null
                                                ? cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD
                                                : cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.valueOf(
                                                                entity.getDifficulty()),
                                entity.getExperienceJson() == null
                                                ? null
                                                : jsonCodec.read(
                                                                entity.getExperienceJson(),
                                                                cn.edu.whu.zhizhida.interview.api.InterviewExperience.class));
        }

        private InterviewHistoryItem toHistoryItem(InterviewSessionEntity entity) {
                return new InterviewHistoryItem(
                                entity.getSessionId(),
                                entity.getTargetRole(),
                                entity.getResumeId(),
                                entity.getMode() == null
                                                ? InterviewMode.GENERAL
                                                : InterviewMode.valueOf(entity.getMode()),
                                entity.getTotalRounds() == null ? 3 : entity.getTotalRounds(),
                                InterviewState.valueOf(entity.getState()),
                                entity.getTurnCount(),
                                entity.getAnsweredCount(),
                                entity.getPreview(),
                                toInstant(entity.getCreatedAt()),
                                toInstant(entity.getUpdatedAt()));
        }

        private String truncatePreview(String value) {
                String normalized = value == null ? "" : value.trim();
                return normalized.codePointCount(0, normalized.length()) <= 160
                                ? normalized
                                : normalized.substring(0, normalized.offsetByCodePoints(0, 160)) + "…";
        }

        private java.time.Instant toInstant(LocalDateTime value) {
                return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
        }
}
