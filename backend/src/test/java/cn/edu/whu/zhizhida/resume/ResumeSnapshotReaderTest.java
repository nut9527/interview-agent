package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeSnapshotReaderTest {
    @Test
    void queriesWithCurrentUserAndPreservesRevision() {
        ResumeRepository repository = mock(ResumeRepository.class);
        var snapshot = new ResumeProfile(7L, "本人", "Java", "", List.of(), List.of(), List.of(), List.of(), List.of(), "classic", 4L);
        when(repository.findById(2L, 7L)).thenReturn(Optional.of(snapshot));
        var reader = new DefaultResumeSnapshotReader(repository, () -> 2L);
        assertThat(reader.requireOwned(7L)).isEqualTo(snapshot);
        assertThatThrownBy(() -> reader.requireOwned(8L)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository).findById(2L, 7L);
        verify(repository).findById(2L, 8L);
        verifyNoMoreInteractions(repository);
    }
}
