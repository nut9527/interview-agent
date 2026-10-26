package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService;

import cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    private InterviewAiService interviewAiService;
    @Mock
    private ResumeSnapshotReader resumeRepository;

    private InterviewStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        lenient().when(sessionRepository.save(eq(USER_ID), any(InterviewSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(sessionRepository.tryAcquireAnswer(eq(USER_ID), any(), anyInt(), any(), anyInt())).thenReturn(true);
        lenient().when(sessionRepository.completeAnswer(eq(USER_ID), any(), anyInt(), any())).thenReturn(true);
        stateMachine = new InterviewStateMachine(
                sessionRepository,
                currentUserProvider,
                interviewAiService,
                resumeRepository,
                30, null, cn.edu.whu.zhizhida.observability.BusinessMetrics.disabled()
        );
    }

    @Test
    void shouldListOnlyCurrentUsersInterviewHistory() {
        InterviewHistoryItem item = new InterviewHistoryItem(
                "session-history",
                "Java 后端工程师",
                7L,
                InterviewState.FINISHED,
                3,
                3,
                "综合总结",
                Instant.parse("2026-07-13T10:00:00Z"),
                Instant.parse("2026-07-13T10:10:00Z")
        );
        when(sessionRepository.findAll(USER_ID)).thenReturn(List.of(item));

        List<InterviewHistoryItem> history = stateMachine.listHistory();

        assertThat(history).containsExactly(item);
        verify(sessionRepository).findAll(USER_ID);
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
    void shouldStartSessionWithAiGeneratedOpeningQuestion() {
        when(interviewAiService.openingQuestion(eq("Java 后端工程师"), eq("Spring Boot 项目经验")))
                .thenReturn("请介绍一个最相关的项目。" );

        InterviewSession session = stateMachine.start(
                new InterviewStartRequest("Java 后端工程师", "Spring Boot 项目经验", null)
        );

        assertThat(session.state()).isEqualTo(InterviewState.WAITING_ANSWER);
        assertThat(session.turnCount()).isEqualTo(1);
        assertThat(session.turns()).extracting(InterviewTurn::speaker)
                .containsExactly("system", "interviewer");
        assertThat(session.turns().get(1).content()).isEqualTo("请介绍一个最相关的项目。" );
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
        when(interviewAiService.openingQuestion(eq("Java 后端工程师"), any()))
                .thenReturn("请介绍订单系统中的性能优化。" );

        InterviewSession session = stateMachine.start(
                new InterviewStartRequest("Java 后端工程师", "不应采用这段手填摘要", 7L)
        );

        assertThat(session.resumeId()).isEqualTo(7L);
        assertThat(session.resumeSnapshot())
                .contains("技能：Java；MySQL")
                .contains("项目经历：订单系统：接口耗时降低 30%");
        assertThat(session.resumeSnapshot()).doesNotContain("不应采用这段手填摘要");
        assertThat(session.turns().get(0).content()).contains("已载入所选简历");
        verify(resumeRepository).requireOwned(7L);
        verify(interviewAiService).openingQuestion("Java 后端工程师", session.resumeSnapshot());
    }

    @Test
    void shouldRejectResumeThatDoesNotBelongToCurrentUser() {
        when(resumeRepository.requireOwned(99L)).thenThrow(new ResourceNotFoundException("resume not found"));

        assertThatThrownBy(() -> stateMachine.start(
                new InterviewStartRequest("Java 后端工程师", null, 99L)
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("resume not found");

        verify(interviewAiService, never()).openingQuestion(any(), any());
        verify(sessionRepository, never()).save(any(), any());
    }

    @Test
    void shouldSnapshotSelectedJobResumeRevisionAndConfiguredMode() {
        ResumeProfile resume = new ResumeProfile(7L, "张三", "Java 后端", "原始经历", List.of("Java"),
                List.of(), List.of("订单系统"), List.of(), List.of(), "classic", 5L);
        when(resumeRepository.requireOwned(7L)).thenReturn(resume);
        when(interviewAiService.openingQuestion(eq("Java 后端"), any())).thenReturn("请介绍订单项目");

        InterviewSession session = stateMachine.start(new InterviewStartRequest("Java 后端", null, 7L, "负责订单接口", InterviewMode.PROJECT_DEEP_DIVE, 2, null, null, null));

        assertThat(session.resumeRevision()).isEqualTo(5L);
        assertThat(session.resumeSnapshot()).contains("原始经历", "订单系统");
        assertThat(session.jobId()).isNull();
        assertThat(session.jobSnapshot()).contains("负责订单接口");
        assertThat(session.mode()).isEqualTo(InterviewMode.PROJECT_DEEP_DIVE);
        assertThat(session.totalRounds()).isEqualTo(2);
        verify(interviewAiService).openingQuestion(eq("Java 后端"),
                org.mockito.ArgumentMatchers.contains("项目深挖"));
    }

    @Test
    void shouldFinishAndReplayAtConfiguredSecondRound() {
        InterviewSession existing = new InterviewSession("session-two", "Java", null, "", null, null,
                "岗位：Java", InterviewMode.BACKEND_BASICS, 2, null, InterviewState.WAITING_ANSWER, 2,
                List.of(new InterviewTurn("interviewer", "第一题"),
                        new InterviewTurn("user", "第一轮回答", "first-request"),
                        new InterviewTurn("system", "反馈"), new InterviewTurn("interviewer", "第二题")));
        when(sessionRepository.findById(USER_ID, "session-two")).thenReturn(Optional.of(existing));
        when(interviewAiService.reviewAnswer(eq("Java"),
                org.mockito.ArgumentMatchers.contains("后端基础"), any(), eq(2), eq(true)))
                .thenReturn(new InterviewAiReply("反馈", "", "总结"));
        InterviewAnswerRequest answer = new InterviewAnswerRequest("第二轮回答", "second-request", 2);

        InterviewSession finished = stateMachine.answer("session-two", answer);
        assertThat(finished.state()).isEqualTo(InterviewState.FINISHED);
        assertThat(finished.turnCount()).isEqualTo(2);
        assertThat(finished.totalRounds()).isEqualTo(2);
        when(sessionRepository.findById(USER_ID, "session-two")).thenReturn(Optional.of(finished));
        assertThat(stateMachine.answer("session-two", answer)).isEqualTo(finished);
    }

    @Test
    void shouldPersistAiFeedbackAndFollowUpForIntermediateRound() {
        InterviewSession existing = new InterviewSession(
                "session-1",
                "Java 后端工程师",
                7L,
                "项目经历：订单系统优化",
                InterviewState.WAITING_ANSWER,
                1,
                List.of(
                        new InterviewTurn("system", "面试开始"),
                        new InterviewTurn("interviewer", "请介绍项目")
                )
        );
        when(sessionRepository.findById(USER_ID, "session-1")).thenReturn(Optional.of(existing));
        when(interviewAiService.reviewAnswer(
                eq("Java 后端工程师"),
                eq("项目经历：订单系统优化"),
                any(),
                eq(1),
                eq(false)
        ))
                .thenReturn(new InterviewAiReply(
                        "优点是职责明确；建议补充量化结果。",
                        "你如何验证优化效果？",
                        ""
                ));

        InterviewSession updated = stateMachine.answer(
                "session-1",
                new InterviewAnswerRequest("我负责接口设计和实现。")
        );

        assertThat(updated.state()).isEqualTo(InterviewState.WAITING_ANSWER);
        assertThat(updated.turnCount()).isEqualTo(2);
        assertThat(updated.turns()).extracting(InterviewTurn::speaker)
                .containsExactly("system", "interviewer", "user", "system", "interviewer");
        assertThat(updated.turns().get(3).content()).contains("建议补充量化结果");
        assertThat(updated.turns().get(4).content()).isEqualTo("你如何验证优化效果？");
        verify(sessionRepository).completeAnswer(eq(USER_ID), eq(updated), anyInt(), any());
    }

    @Test
    void shouldFinishAfterThirdAiReviewedAnswer() {
        InterviewSession existing = new InterviewSession(
                "session-3",
                "Java 后端工程师",
                null,
                "",
                InterviewState.WAITING_ANSWER,
                3,
                List.of(
                        new InterviewTurn("user", "第一轮回答"),
                        new InterviewTurn("user", "第二轮回答"),
                        new InterviewTurn("interviewer", "请复盘改进点")
                )
        );
        when(sessionRepository.findById(USER_ID, "session-3")).thenReturn(Optional.of(existing));
        when(interviewAiService.reviewAnswer(eq("Java 后端工程师"), eq(""), any(), eq(3), eq(true)))
                .thenReturn(new InterviewAiReply(
                        "复盘方向明确；建议说明优先级依据。",
                        "",
                        "综合总结：岗位基础较好，继续补充可量化成果。"
                ));

        InterviewSession updated = stateMachine.answer(
                "session-3",
                new InterviewAnswerRequest("第三轮回答")
        );

        assertThat(updated.state()).isEqualTo(InterviewState.FINISHED);
        assertThat(updated.turnCount()).isEqualTo(3);
        assertThat(updated.turns().get(updated.turns().size() - 1).content())
                .startsWith("综合总结");
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("answer when session not found should throw ResourceNotFoundException")
        void shouldThrowWhenAnsweringNonexistentSession() {
            when(sessionRepository.findById(USER_ID, "nonexistent-session"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> stateMachine.answer(
                    "nonexistent-session",
                    new InterviewAnswerRequest("回答内容")
            ))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("interview session not found");

            verify(interviewAiService, never()).reviewAnswer(any(), any(), any(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("answer on already FINISHED session should return session unchanged without AI call")
        void shouldReturnUnchangedWhenAnsweringFinishedSession() {
            InterviewSession finished = new InterviewSession(
                    "session-finished",
                    "Java 后端工程师",
                    null,
                    "",
                    InterviewState.FINISHED,
                    3,
                    List.of(
                            new InterviewTurn("system", "面试开始"),
                            new InterviewTurn("interviewer", "提问"),
                            new InterviewTurn("user", "回答1"),
                            new InterviewTurn("system", "反馈1"),
                            new InterviewTurn("interviewer", "提问2"),
                            new InterviewTurn("user", "回答2"),
                            new InterviewTurn("system", "反馈2"),
                            new InterviewTurn("interviewer", "提问3"),
                            new InterviewTurn("user", "回答3"),
                            new InterviewTurn("system", "反馈3"),
                            new InterviewTurn("interviewer", "综合总结")
                    )
            );
            when(sessionRepository.findById(USER_ID, "session-finished"))
                    .thenReturn(Optional.of(finished));

            InterviewSession result = stateMachine.answer(
                    "session-finished",
                    new InterviewAnswerRequest("额外的回答")
            );

            assertThat(result).isEqualTo(finished);
            verify(interviewAiService, never()).reviewAnswer(any(), any(), any(), anyInt(), anyBoolean());
            verify(sessionRepository, never()).save(any(), any());
        }

        @Test
        @DisplayName("start with null resumeId should use resumeSummary and show default start message")
        void shouldStartWithNullResumeIdAndUseDefaultMessage() {
            when(interviewAiService.openingQuestion(eq("产品经理"), eq("产品设计经验")))
                    .thenReturn("请介绍一个产品设计案例。" );

            InterviewSession session = stateMachine.start(
                    new InterviewStartRequest("产品经理", "产品设计经验", null)
            );

            assertThat(session.resumeId()).isNull();
            assertThat(session.resumeSnapshot()).isEqualTo("产品设计经验");
            assertThat(session.turns().get(0).content()).contains("面试开始");
            assertThat(session.turns().get(0).content()).doesNotContain("已载入所选简历");
            assertThat(session.turns().get(1).content()).isEqualTo("请介绍一个产品设计案例。" );
        }

        @Test
        @DisplayName("answer should correctly track turn count through multiple rounds")
        void shouldTrackTurnCountThroughMultipleRounds() {
            // 第一轮回答
            InterviewSession round1 = new InterviewSession(
                    "session-multi",
                    "Java 后端工程师",
                    null,
                    "",
                    InterviewState.WAITING_ANSWER,
                    1,
                    List.of(
                            new InterviewTurn("system", "面试开始"),
                            new InterviewTurn("interviewer", "请介绍项目")
                    )
            );
            when(sessionRepository.findById(USER_ID, "session-multi"))
                    .thenReturn(Optional.of(round1));
            when(interviewAiService.reviewAnswer(eq("Java 后端工程师"), eq(""), any(), eq(1), eq(false)))
                    .thenReturn(new InterviewAiReply("反馈1", "追问1", ""));

            InterviewSession afterRound1 = stateMachine.answer(
                    "session-multi",
                    new InterviewAnswerRequest("回答1")
            );

            assertThat(afterRound1.turnCount()).isEqualTo(2);
            assertThat(afterRound1.state()).isEqualTo(InterviewState.WAITING_ANSWER);

            // 第二轮回答
            when(sessionRepository.findById(USER_ID, "session-multi"))
                    .thenReturn(Optional.of(afterRound1));
            when(interviewAiService.reviewAnswer(eq("Java 后端工程师"), eq(""), any(), eq(2), eq(false)))
                    .thenReturn(new InterviewAiReply("反馈2", "追问2", ""));

            InterviewSession afterRound2 = stateMachine.answer(
                    "session-multi",
                    new InterviewAnswerRequest("回答2")
            );

            assertThat(afterRound2.turnCount()).isEqualTo(3);
            assertThat(afterRound2.state()).isEqualTo(InterviewState.WAITING_ANSWER);

            // 第三轮回答，结束面试。
            when(sessionRepository.findById(USER_ID, "session-multi"))
                    .thenReturn(Optional.of(afterRound2));
            when(interviewAiService.reviewAnswer(eq("Java 后端工程师"), eq(""), any(), eq(3), eq(true)))
                    .thenReturn(new InterviewAiReply("反馈3", "", "综合总结"));

            InterviewSession afterRound3 = stateMachine.answer(
                    "session-multi",
                    new InterviewAnswerRequest("回答3")
            );

            assertThat(afterRound3.turnCount()).isEqualTo(3);
            assertThat(afterRound3.state()).isEqualTo(InterviewState.FINISHED);
        }
    }
}
