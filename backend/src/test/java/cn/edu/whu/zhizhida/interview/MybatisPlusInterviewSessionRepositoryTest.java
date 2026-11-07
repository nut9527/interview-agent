package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.InterviewSourceAccess;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionEntity;
import cn.edu.whu.zhizhida.interview.persistence.InterviewSessionMapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class MybatisPlusInterviewSessionRepositoryTest {

    @Mock private InterviewSessionMapper mapper;

    private MybatisPlusInterviewSessionRepository repository;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "interview-test"),
                InterviewSessionEntity.class);
        repository =
                new MybatisPlusInterviewSessionRepository(
                        mapper,
                        new JsonColumnCodec(new tools.jackson.databind.json.JsonMapper()),
                        new InterviewSourceGuard(mock(InterviewSourceAccess.class)),
                        Optional.empty());
    }

    @Test
    void retriesReadProjectedSummariesForTheRequestedOwnerAndSource() {
        InterviewSessionEntity entity = historyEntity();
        entity.setTurnsJson(null);
        entity.setAnsweredCount(1);
        entity.setPreview("请继续说明优化结果");
        when(mapper.selectCount(any())).thenReturn(5L);
        when(mapper.selectList(any())).thenReturn(List.of(entity));

        var history = repository.retries(42L, "source-id", new PageRequest(2, 4));

        assertThat(history.total()).isEqualTo(5);
        assertThat(history.items())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.sessionId()).isEqualTo("session-1");
                            assertThat(item.answeredCount()).isEqualTo(1);
                            assertThat(item.preview()).isEqualTo("请继续说明优化结果");
                        });
        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("answered_count", "preview")
                .doesNotContain("turns_json", "agent_json");
        assertThat(captor.getValue().getSqlSegment())
                .contains(
                        "user_id",
                        "$.training.retry.sourceSessionId",
                        "updated_at DESC",
                        "session_id DESC",
                        "LIMIT 4 OFFSET 4");
    }

    @Test
    void practiceHistoryLoadsOnlyEightFinishedCheckpointsWithinUserAndMode() {
        var checkpoint =
                new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy()
                        .plan(InterviewMode.GENERAL, 6, "", "model");
        var row = new InterviewSessionEntity();
        row.setAgentJson(
                new JsonColumnCodec(new tools.jackson.databind.json.JsonMapper())
                        .write(checkpoint));
        when(mapper.selectList(any())).thenReturn(List.of(row));
        assertThat(repository.recentAgentStates(42L, InterviewMode.GENERAL))
                .containsExactly(checkpoint);
        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect()).isEqualTo("agent_json");
        assertThat(captor.getValue().getSqlSegment())
                .contains("user_id", "mode", "state", "LIMIT 8");
        assertThat(
                        ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                                InterviewSessionEntity>)
                                        captor.getValue())
                                .getParamNameValuePairs()
                                .values())
                .contains(42L, "GENERAL", "FINISHED");
    }

    @Test
    void shouldDeleteHistoryWithinCurrentUserScope() {
        when(mapper.selectOne(any())).thenReturn(historyEntity());
        when(mapper.delete(org.mockito.ArgumentMatchers.<Wrapper<InterviewSessionEntity>>any()))
                .thenReturn(1);

        assertThat(repository.deleteById(42L, "session-1")).isTrue();

        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("session_id")
                .containsIgnoringCase("user_id");
    }

    @Test
    void shouldFindByIdReturnSessionWithTurnsDeserialized() {
        InterviewSessionEntity entity = historyEntity();
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<InterviewSession> result = repository.findById(42L, "session-1");

        assertThat(result).isPresent();
        InterviewSession session = result.get();
        assertThat(session.sessionId()).isEqualTo("session-1");
        assertThat(session.targetRole()).isEqualTo("Java 后端工程师");
        assertThat(session.resumeId()).isEqualTo(7L);
        assertThat(session.state()).isEqualTo(InterviewState.WAITING_ANSWER);
        assertThat(session.turnCount()).isEqualTo(2);
        assertThat(session.turns()).hasSize(3);

        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("session_id")
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("LIMIT 1");
    }

    @Test
    void shouldFindByIdReturnEmptyWhenNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        Optional<InterviewSession> result = repository.findById(42L, "non-existent");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldFindByIdForUpdateUseForUpdateLock() {
        InterviewSessionEntity entity = historyEntity();
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<InterviewSession> result = repository.findByIdForUpdate(42L, "session-1");

        assertThat(result).isPresent();
        assertThat(result.get().sessionId()).isEqualTo("session-1");

        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("LIMIT 1 FOR UPDATE");
    }

    @Test
    void shouldSaveCreateNewEntityWhenNotExisting() {
        InterviewSession session =
                new InterviewSession(
                        "new-session",
                        "目标岗位",
                        5L,
                        "简历快照",
                        8L,
                        9L,
                        "岗位快照",
                        InterviewMode.PROJECT_DEEP_DIVE,
                        2,
                        null,
                        InterviewState.CREATED,
                        0,
                        List.of());
        when(mapper.selectById("new-session")).thenReturn(null);

        InterviewSession saved = repository.save(42L, session);

        assertThat(saved).isSameAs(session);

        ArgumentCaptor<InterviewSessionEntity> captor =
                ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getResumeRevision()).isEqualTo(8L);
        assertThat(captor.getValue().getJobSnapshot()).isEqualTo("岗位快照");
        assertThat(captor.getValue().getMode()).isEqualTo("PROJECT_DEEP_DIVE");
        assertThat(captor.getValue().getTotalRounds()).isEqualTo(2);
        assertThat(captor.getValue().getPreview()).isEqualTo("暂无面试内容");
        assertThat(captor.getValue().getAnsweredCount()).isZero();
    }

    @Test
    void shouldSaveUpdateExistingEntity() {
        String question = "题".repeat(159) + "😀";
        InterviewSession session =
                new InterviewSession(
                        "existing-session",
                        "更新岗位",
                        5L,
                        "快照",
                        InterviewState.ASKING,
                        3,
                        List.of(
                                new InterviewTurn("interviewer", "首问"),
                                new InterviewTurn("user", "回答"),
                                new InterviewTurn("interviewer", "\t" + question + "尾 \n")));
        InterviewSessionEntity existing = historyEntity();
        when(mapper.selectById("existing-session")).thenReturn(existing);
        when(mapper.update(any(InterviewSessionEntity.class), any(Wrapper.class))).thenReturn(1);

        InterviewSession saved = repository.save(42L, session);

        assertThat(saved).isSameAs(session);

        ArgumentCaptor<InterviewSessionEntity> entityCaptor =
                ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(mapper)
                .update(
                        entityCaptor.capture(),
                        any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        InterviewSessionEntity entity = entityCaptor.getValue();
        assertThat(entity.getSessionId()).isEqualTo("existing-session");
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.getAnsweredCount()).isEqualTo(1);
        assertThat(entity.getPreview()).isEqualTo(question + "…");
    }

    @Test
    void shouldDeleteAllByUserIdUseUserScope() {
        when(mapper.delete(any())).thenReturn(5);

        int deleted = repository.deleteAllByUserId(42L);

        assertThat(deleted).isEqualTo(5);

        ArgumentCaptor<Wrapper<InterviewSessionEntity>> captor = wrapperCaptor();
        verify(mapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).containsIgnoringCase("user_id");
    }

    @Test
    void emptyHistoryPageHasNoItems() {
        assertThat(repository.page(42L, new PageRequest(1, 20)).items()).isEmpty();
    }

    @Test
    void shouldHandleResumeSnapshotBeingNull() {
        InterviewSessionEntity entity = historyEntity();
        entity.setResumeSnapshot(null);
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<InterviewSession> result = repository.findById(42L, "session-1");

        assertThat(result).isPresent();
        assertThat(result.get().resumeSnapshot()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<InterviewSessionEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass(
                (Class<Wrapper<InterviewSessionEntity>>) (Class<?>) Wrapper.class);
    }

    private InterviewSessionEntity historyEntity() {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setSessionId("session-1");
        entity.setUserId(42L);
        entity.setTargetRole("Java 后端工程师");
        entity.setResumeId(7L);
        entity.setResumeSnapshot("技能：Java");
        entity.setState(InterviewState.WAITING_ANSWER.name());
        entity.setTurnCount(2);
        entity.setTurnsJson(
                """
                [
                  {"speaker":"interviewer","content":"请介绍项目"},
                  {"speaker":"user","content":"我负责接口优化"},
                  {"speaker":"interviewer","content":"请继续说明优化结果"}
                ]
                """);
        entity.setCreatedAt(LocalDateTime.of(2026, 7, 13, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 13, 10, 10));
        return entity;
    }
}
