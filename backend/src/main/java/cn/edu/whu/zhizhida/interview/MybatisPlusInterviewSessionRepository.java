package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.InterviewPreparation;

import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionEntity;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话的持久化适配器，以用户归属、题号和租约令牌共同约束回答写入。
 * 租约是否过期由数据库时间判断，使不同应用实例使用同一到期标准。
 */
@Repository
public class MybatisPlusInterviewSessionRepository implements InterviewSessionRepository {

    private static final TypeReference<List<InterviewTurn>> TURN_LIST = new TypeReference<>() {
    };

    private final InterviewSessionMapper interviewSessionMapper;
    private final JsonColumnCodec jsonCodec;

    public MybatisPlusInterviewSessionRepository(InterviewSessionMapper interviewSessionMapper,
                                                 JsonColumnCodec jsonCodec) {
        this.interviewSessionMapper = interviewSessionMapper;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public InterviewSession save(Long userId, InterviewSession session) {
        InterviewSessionEntity entity = toEntity(userId, session);
        InterviewSessionEntity existing = interviewSessionMapper.selectById(session.sessionId());
        if (existing == null) {
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(entity.getCreatedAt());
            interviewSessionMapper.insert(entity);
        } else {
            entity.setUpdatedAt(LocalDateTime.now());
            interviewSessionMapper.update(
                    entity,
                    Wrappers.<InterviewSessionEntity>lambdaUpdate()
                            .eq(InterviewSessionEntity::getSessionId, session.sessionId())
                            .eq(InterviewSessionEntity::getUserId, userId)
            );
        }
        return session;
    }

    @Override
    public List<InterviewHistoryItem> findAll(Long userId) {
        return interviewSessionMapper.selectList(
                        Wrappers.<InterviewSessionEntity>lambdaQuery()
                                .eq(InterviewSessionEntity::getUserId, userId)
                                .orderByDesc(InterviewSessionEntity::getUpdatedAt)
                                .last("LIMIT 100")
                ).stream()
                .map(this::toHistoryItem)
                .toList();
    }

    @Override
    public Optional<InterviewSession> findById(Long userId, String sessionId) {
        return findEntity(userId, sessionId, false).map(this::toSession);
    }

    @Override
    public List<cn.edu.whu.zhizhida.interview.api.InterviewAgentState> recentAgentStates(Long userId, InterviewMode mode) {
        return interviewSessionMapper.selectList(Wrappers.<InterviewSessionEntity>lambdaQuery()
                        .select(InterviewSessionEntity::getAgentJson)
                        .eq(InterviewSessionEntity::getUserId, userId)
                        .eq(InterviewSessionEntity::getMode, mode.name())
                        .eq(InterviewSessionEntity::getState, InterviewState.FINISHED.name())
                        .isNotNull(InterviewSessionEntity::getAgentJson)
                        .orderByDesc(InterviewSessionEntity::getUpdatedAt).orderByDesc(InterviewSessionEntity::getSessionId)
                        .last("LIMIT 8"))
                .stream().map(e -> jsonCodec.read(e.getAgentJson(), cn.edu.whu.zhizhida.interview.api.InterviewAgentState.class)).toList();
    }

    @Override
    public Optional<InterviewSession> findByIdForUpdate(Long userId, String sessionId) {
        return findEntity(userId, sessionId, true).map(this::toSession);
    }

    @Override
    public boolean tryAcquireAnswer(Long userId, String sessionId, int expectedTurn, String leaseToken, int leaseSeconds) {
        // 单条条件更新争抢当前题目的处理权；已占用且未过期的租约不能被覆盖。
        return interviewSessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
                .eq(InterviewSessionEntity::getUserId, userId)
                .eq(InterviewSessionEntity::getSessionId, sessionId)
                .eq(InterviewSessionEntity::getState, InterviewState.WAITING_ANSWER.name())
                .eq(InterviewSessionEntity::getTurnCount, expectedTurn)
                .and(lease -> lease.isNull(InterviewSessionEntity::getAnswerLeaseToken)
                        .or().apply("answer_lease_until <= CURRENT_TIMESTAMP(6)"))
                .set(InterviewSessionEntity::getAnswerLeaseToken, leaseToken)
                .setSql("answer_lease_until = TIMESTAMPADD(SECOND, {0}, CURRENT_TIMESTAMP(6))", leaseSeconds)) == 1;
    }

    @Override
    public boolean completeAnswer(Long userId, InterviewSession updated, int expectedTurn, String leaseToken) {
        InterviewSessionEntity entity = toEntity(userId, updated);
        entity.setUpdatedAt(LocalDateTime.now());
        // 提交时再次校验租约与题号，拦截超时调用或被其他执行者替代后的迟到结果。
        return interviewSessionMapper.update(entity, Wrappers.<InterviewSessionEntity>lambdaUpdate()
                .eq(InterviewSessionEntity::getUserId, userId)
                .eq(InterviewSessionEntity::getSessionId, updated.sessionId())
                .eq(InterviewSessionEntity::getState, InterviewState.WAITING_ANSWER.name())
                .eq(InterviewSessionEntity::getTurnCount, expectedTurn)
                .eq(InterviewSessionEntity::getAnswerLeaseToken, leaseToken)
                .apply("answer_lease_until > CURRENT_TIMESTAMP(6)")
                .set(InterviewSessionEntity::getAnswerLeaseToken, null)
                .set(InterviewSessionEntity::getAnswerLeaseUntil, null)) == 1;
    }

    @Override
    public void releaseAnswer(Long userId, String sessionId, String leaseToken) {
        interviewSessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
                .eq(InterviewSessionEntity::getUserId, userId)
                .eq(InterviewSessionEntity::getSessionId, sessionId)
                .eq(InterviewSessionEntity::getAnswerLeaseToken, leaseToken)
                .set(InterviewSessionEntity::getAnswerLeaseToken, null)
                .set(InterviewSessionEntity::getAnswerLeaseUntil, null));
    }

    @Override
    public boolean deleteById(Long userId, String sessionId) {
        return interviewSessionMapper.delete(
                Wrappers.<InterviewSessionEntity>lambdaQuery()
                        .eq(InterviewSessionEntity::getSessionId, sessionId)
                        .eq(InterviewSessionEntity::getUserId, userId)
        ) > 0;
    }

    private Optional<InterviewSessionEntity> findEntity(Long userId, String sessionId, boolean forUpdate) {
        InterviewSessionEntity entity = interviewSessionMapper.selectOne(
                Wrappers.<InterviewSessionEntity>lambdaQuery()
                        .eq(InterviewSessionEntity::getSessionId, sessionId)
                        .eq(InterviewSessionEntity::getUserId, userId)
                        .last(forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    @Override
    public int deleteAllByUserId(Long userId) {
        return interviewSessionMapper.delete(
                Wrappers.<InterviewSessionEntity>lambdaQuery()
                        .eq(InterviewSessionEntity::getUserId, userId)
        );
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
        entity.setTotalRounds(session.totalRounds());
        entity.setState(session.state().name());
        entity.setTurnCount(session.turnCount());
        entity.setTurnsJson(jsonCodec.write(session.turns()));
        entity.setPreparationJson(session.preparation() == null ? null : jsonCodec.write(session.preparation()));
        entity.setAgentJson(session.agent() == null ? null : jsonCodec.write(session.agent()));
        entity.setAnsweredCount((int) session.turns().stream().filter(t -> "user".equals(t.speaker())).count());
        entity.setPreview(session.turns().stream().filter(t -> "interviewer".equals(t.speaker()))
                .reduce((a, b) -> b).map(InterviewTurn::content).map(this::truncatePreview).orElse("暂无面试内容"));
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
                entity.getMode() == null ? InterviewMode.GENERAL : InterviewMode.valueOf(entity.getMode()),
                entity.getTotalRounds() == null ? 3 : entity.getTotalRounds(),
                toInstant(entity.getAnswerLeaseUntil()),
                InterviewState.valueOf(entity.getState()),
                entity.getTurnCount(),
                jsonCodec.read(entity.getTurnsJson(), TURN_LIST),
                entity.getPreparationJson() == null ? null : jsonCodec.read(entity.getPreparationJson(), InterviewPreparation.class),
                entity.getAgentJson() == null ? null : jsonCodec.read(entity.getAgentJson(), cn.edu.whu.zhizhida.interview.api.InterviewAgentState.class),
                entity.getDifficulty() == null ? cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD : cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.valueOf(entity.getDifficulty())
        );
    }

    private InterviewHistoryItem toHistoryItem(InterviewSessionEntity entity) {
        List<InterviewTurn> turns = jsonCodec.read(entity.getTurnsJson(), TURN_LIST);
        int answeredCount = (int) turns.stream()
                .filter(turn -> "user".equals(turn.speaker()))
                .count();
        String preview = turns.stream()
                .filter(turn -> "interviewer".equals(turn.speaker()))
                .reduce((first, second) -> second)
                .map(InterviewTurn::content)
                .map(this::truncatePreview)
                .orElse("暂无面试内容");
        return new InterviewHistoryItem(
                entity.getSessionId(),
                entity.getTargetRole(),
                entity.getResumeId(),
                entity.getMode() == null ? InterviewMode.GENERAL : InterviewMode.valueOf(entity.getMode()),
                entity.getTotalRounds() == null ? 3 : entity.getTotalRounds(),
                InterviewState.valueOf(entity.getState()),
                entity.getTurnCount(),
                answeredCount,
                preview,
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt())
        );
    }

    private String truncatePreview(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.codePointCount(0, normalized.length()) <= 160 ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, 160)) + "…";
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
    @Override
    public PageResult<InterviewHistoryItem> page(Long userId, PageRequest page) {
        long total = interviewSessionMapper.selectCount(Wrappers.<InterviewSessionEntity>lambdaQuery().eq(InterviewSessionEntity::getUserId, userId));
        var items = interviewSessionMapper.selectList(Wrappers.<InterviewSessionEntity>lambdaQuery()
                .select(InterviewSessionEntity::getSessionId, InterviewSessionEntity::getTargetRole, InterviewSessionEntity::getResumeId, InterviewSessionEntity::getMode, InterviewSessionEntity::getTotalRounds, InterviewSessionEntity::getState, InterviewSessionEntity::getTurnCount, InterviewSessionEntity::getAnsweredCount, InterviewSessionEntity::getPreview, InterviewSessionEntity::getCreatedAt, InterviewSessionEntity::getUpdatedAt)
                .eq(InterviewSessionEntity::getUserId, userId).orderByDesc(InterviewSessionEntity::getUpdatedAt, InterviewSessionEntity::getSessionId).last(page.limitSql()))
                .stream().map(e -> new InterviewHistoryItem(e.getSessionId(), e.getTargetRole(), e.getResumeId(), e.getMode() == null ? InterviewMode.GENERAL : InterviewMode.valueOf(e.getMode()), e.getTotalRounds() == null ? 3 : e.getTotalRounds(), InterviewState.valueOf(e.getState()), e.getTurnCount(), e.getAnsweredCount(), e.getPreview(), toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()))).toList();
        return new PageResult<>(items, total, page);
    }


}
