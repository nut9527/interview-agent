package cn.edu.whu.zhizhida.interview;



import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewStateMachineTest {

    private static final Long USER_ID = 42L;

    @Mock
    private InterviewSessionRepository sessionRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ResumeSnapshotReader resumeRepository;

    private InterviewStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        stateMachine = InterviewMachineFixture.machine(
                sessionRepository,
                currentUserProvider,
                cn.edu.whu.zhizhida.ai.api.AiMode.MOCK,
                resumeRepository
        );
    }

    @Test
    void shouldListOnlyCurrentUsersInterviewHistory() {
        var page = new PageRequest(2, 10);
        var result = new PageResult<InterviewHistoryItem>(List.of(), 3, page);
        when(sessionRepository.page(USER_ID, page)).thenReturn(result);
        assertThat(stateMachine.listHistory(page)).isSameAs(result);
    }

    @Test
    void shouldFindOwnedHistoricalSession() {
        InterviewSession existing = new InterviewSession(
                "session-detail",
                "Java 后端工程师",
                null,
                "",
                InterviewState.FINISHED,
                3,
                List.of(new InterviewTurn("interviewer", "综合总结"))
        );
        when(sessionRepository.findById(USER_ID, "session-detail")).thenReturn(Optional.of(existing));

        assertThat(stateMachine.findSession("session-detail")).isEqualTo(existing);
        verify(sessionRepository).findById(USER_ID, "session-detail");
    }

    @Test
    void shouldDeleteOnlyCurrentUsersHistoricalSession() {
        when(sessionRepository.deleteById(USER_ID, "session-delete")).thenReturn(true);

        stateMachine.deleteSession("session-delete");

        verify(sessionRepository).deleteById(USER_ID, "session-delete");
    }

    @Test
    void shouldReturnNotFoundWhenHistoricalSessionIsNotOwned() {
        when(sessionRepository.findById(USER_ID, "session-other")).thenReturn(Optional.empty());
        when(sessionRepository.deleteById(USER_ID, "session-other")).thenReturn(false);

        assertThatThrownBy(() -> stateMachine.findSession("session-other"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> stateMachine.deleteSession("session-other"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void preparesInputWithoutCallingModelOrWritingSession() {
        var prepared = stateMachine.prepareStart(new InterviewStartRequest("Java 后端工程师", "Spring Boot 项目经验", null));
        assertThat(prepared.turns()).extracting(InterviewTurn::speaker).containsExactly("system");
        verify(sessionRepository, never()).save(any(), any());
    }

    @Test
    void shouldLoadOwnedResumeAndPersistSnapshotWhenStartingSession() {
        ResumeProfile resume = new ResumeProfile(
                7L,
                "张三",
                "Java 后端工程师",
                "负责 Spring Boot 服务开发",
                List.of("Java", "MySQL"),
                List.of("武汉大学 软件工程"),
                List.of("订单系统：接口耗时降低 30%"),
                List.of(),
                List.of("CET-6"),
                "classic"
        );
        when(resumeRepository.requireOwned(7L)).thenReturn(resume);

        InterviewSession session = stateMachine.prepareStart(
                new InterviewStartRequest("Java 后端工程师", "不应采用这段手填摘要", 7L)
        );

        assertThat(session.resumeId()).isEqualTo(7L);
        assertThat(session.resumeSnapshot())
                .contains("技能：Java；MySQL")
                .contains("项目经历：订单系统：接口耗时降低 30%");
        assertThat(session.resumeSnapshot()).doesNotContain("不应采用这段手填摘要");
        assertThat(session.turns().get(0).content()).contains("已载入所选简历");
        verify(resumeRepository).requireOwned(7L);
    }

    @Test
    void shouldRejectResumeThatDoesNotBelongToCurrentUser() {
        when(resumeRepository.requireOwned(99L)).thenThrow(new ResourceNotFoundException("resume not found"));

        assertThatThrownBy(() -> stateMachine.prepareStart(
                new InterviewStartRequest("Java 后端工程师", null, 99L)
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("resume not found");

        verify(sessionRepository, never()).save(any(), any());
    }

    @Test
    void shouldSnapshotSelectedJobResumeRevisionAndConfiguredMode() {
        ResumeProfile resume = new ResumeProfile(7L, "张三", "Java 后端", "原始经历", List.of("Java"),
                List.of(), List.of("订单系统"), List.of(), List.of(), "classic", 5L);
        when(resumeRepository.requireOwned(7L)).thenReturn(resume);

        InterviewSession session = stateMachine.prepareStart(new InterviewStartRequest("Java 后端", null, 7L, "负责订单接口", InterviewMode.PROJECT_DEEP_DIVE, 2, null, null, null));

        assertThat(session.resumeRevision()).isEqualTo(5L);
        assertThat(session.resumeSnapshot()).contains("原始经历", "订单系统");
        assertThat(session.jobId()).isNull();
        assertThat(session.jobSnapshot()).contains("负责订单接口");
        assertThat(session.mode()).isEqualTo(InterviewMode.PROJECT_DEEP_DIVE);
        assertThat(session.totalRounds()).isEqualTo(2);
        assertThat(stateMachine.runContext(session, null, List.of())).contains("项目深挖");
    }

}
