package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.api.TargetJobRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TargetJobServiceTest {
    TargetJobRepository repository = mock(TargetJobRepository.class);
    CurrentUserProvider users = mock(CurrentUserProvider.class);
    ResumeSnapshotReader resumes = mock(ResumeSnapshotReader.class);
    TargetJobService service = new TargetJobService(repository, users, resumes);
    TargetJob job = new TargetJob("job", 2, "Java", "", "", "", null, "Java", List.of(), null);
    @BeforeEach void setup() { when(users.requireUserId()).thenReturn(9L); when(repository.find(9L, "job")).thenReturn(Optional.of(job)); }

    @Test void foreignAndMissingTargetsUseSame404BeforeAccessingResume() {
        assertThatThrownBy(() -> service.analyze("foreign", new TargetJob.AnalyzeRequest(3L, 0L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        verifyNoInteractions(resumes);
    }
    @Test void rejectsStaleAnalysisBeforeReadingResume() {
        assertThatThrownBy(() -> service.analyze("job", new TargetJob.AnalyzeRequest(3L, 1L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        verifyNoInteractions(resumes);
    }
    @Test void updatesAndDeletesCannotIgnoreRevision() {
        assertThatThrownBy(() -> service.update("job", request("", List.of())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.delete("job", 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        verify(repository).delete(9L, "job", 1);
    }
    @ParameterizedTest @ValueSource(strings = {"javascript:alert(1)", "https://name:secret@example.test", "file:///tmp/foo", "https://", "https://bad host"})
    void rejectsUnsafeSourceUrls(String url) {
        assertThatThrownBy(() -> service.update("job", request(url, List.of()))).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).update(anyLong(), any());
    }
    @Test void rejectsDuplicateRequirementsAndQuotesFromDifferentJd() {
        var requirement = new TargetJob.Requirement("r", "Java", TargetJob.Kind.MUST, List.of("Java"), "Java");
        assertThatThrownBy(() -> service.update("job", request("", List.of(requirement, requirement)))).hasMessageContaining("ID");
        var wrongQuote = new TargetJob.Requirement("r", "Java", TargetJob.Kind.MUST, List.of("Java"), "Python");
        assertThatThrownBy(() -> service.update("job", request("", List.of(wrongQuote)))).hasMessageContaining("引文");
    }
    @Test void createRequiresZeroRevision() {
        assertThatThrownBy(() -> service.create(request("", List.of()))).hasMessageContaining("revision");
    }
    private TargetJobRequest request(String url, List<TargetJob.Requirement> requirements) {
        return new TargetJobRequest(2L, "Java", null, null, url, null, "Java", requirements);
    }
}
