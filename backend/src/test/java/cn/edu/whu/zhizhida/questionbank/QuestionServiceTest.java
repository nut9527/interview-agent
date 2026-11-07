package cn.edu.whu.zhizhida.questionbank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.questionbank.api.QuestionTopic;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class QuestionServiceTest {
    private final QuestionRepository repository = mock(QuestionRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final QuestionService service =
            new QuestionService(repository, users, mock(QuestionPracticeHistory.class));

    @Test
    void validatesAndNormalizesSearchBeforePersistence() {
        var page = new PageRequest(2, 12);
        service.page(page, QuestionTopic.JAVA, InterviewDifficulty.STANDARD, "  record  ");
        verify(repository).page(page, QuestionTopic.JAVA, InterviewDifficulty.STANDARD, "record");
        service.page(page, null, null, null);
        verify(repository).page(page, null, null, "");
        assertThatThrownBy(() -> service.page(page, null, null, "字".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRequest(0, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRequest(1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allOperationsRequireIdentityAndMissingQuestionUsesStandardCode() {
        when(repository.find("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.find("missing"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        reset(repository);
        when(users.requireUserId())
                .thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录"));
        assertThatThrownBy(() -> service.page(new PageRequest(1, 12), null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.find("qb-java-001")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(service::metadata).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void topicMappingsRemainCompatibleWithActualServerCatalog() {
        var catalog =
                new InterviewAgentPolicy()
                        .plan(InterviewMode.BACKEND_BASICS, 12, "", "mock")
                        .plan();
        var metadata = service.metadata();
        assertThat(metadata.topics()).hasSize(6);
        assertThat(metadata.difficulties()).hasSize(3);
        for (var topic : metadata.topics()) {
            assertThat(catalog).anyMatch(c -> c.id().equals(topic.competencyId()));
            assertThat(topic.label()).isNotBlank();
        }
    }
}
