package cn.edu.whu.zhizhida.questionbank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static java.util.concurrent.TimeUnit.SECONDS;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.interview.api.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

class QuestionSnapshotCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final JsonMapper json = new JsonMapper();
    final QuestionSnapshotCache cache =
            new QuestionSnapshotCache(redis, json, new SimpleMeterRegistry(), "test");
    final Supplier<QuestionSnapshot> source = mock(Supplier.class);

    QuestionSnapshotCacheTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(source.get()).thenReturn(snapshot(1));
    }

    static QuestionSnapshot snapshot(int version) {
        return new QuestionSnapshot(
                "qb-java-001",
                version,
                "标题",
                "题目",
                "JAVA",
                "foundation",
                InterviewDifficulty.BEGINNER,
                List.of("Java"),
                List.of("a", "b", "c"),
                "参考答案",
                List.of("a", "b"),
                List.of("a", "b"),
                "ORIGINAL",
                null,
                null);
    }

    @Test
    void coldReadWritesJitteredTtlAndHotReadAvoidsArchive() {
        assertThat(cache.read("qb-java-001", 1, source)).isEqualTo(snapshot(1));
        verify(values)
                .set(
                        eq("test:question:snapshot:s1:qb-java-001:v1"),
                        anyString(),
                        argThat(
                                (Duration ttl) ->
                                        ttl.getSeconds() >= 1200 && ttl.getSeconds() <= 1320));
        when(values.get(anyString()))
                .thenReturn(
                        json.writeValueAsString(new QuestionSnapshotCache.Entry(1, snapshot(1))));
        assertThat(cache.read("qb-java-001", 1, source)).isEqualTo(snapshot(1));
        verify(source, times(1)).get();
    }

    @Test
    void concurrentHitsReadTheSameKeyWithoutWaitingForEachOther() throws Exception {
        var bothReading = new CyclicBarrier(2);
        String hit = json.writeValueAsString(new QuestionSnapshotCache.Entry(1, snapshot(1)));
        when(values.get(anyString()))
                .thenAnswer(
                        call -> {
                            bothReading.await(5, SECONDS);
                            return hit;
                        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> cache.read("qb-java-001", 1, source));
            var second = pool.submit(() -> cache.read("qb-java-001", 1, source));
            assertThat(first.get(5, SECONDS)).isEqualTo(snapshot(1));
            assertThat(second.get(5, SECONDS)).isEqualTo(snapshot(1));
            verifyNoInteractions(source);
        }
    }

    @Test
    void concurrentMissSharesArchiveWhileRedisWriteIsPending() throws Exception {
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            writing.countDown();
                            release.await();
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> cache.read("qb-java-001", 1, source));
            try {
                assertThat(writing.await(5, SECONDS)).isTrue();
                var second = pool.submit(() -> cache.read("qb-java-001", 1, source));
                assertThat(second.get(5, SECONDS)).isEqualTo(snapshot(1));
                verify(source, times(1)).get();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, SECONDS)).isEqualTo(snapshot(1));
        }
    }

    @Test
    void callerHoldingTransactionCanUseItsOwnConnectionOnContendedMiss() throws Exception {
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(source.get())
                .thenAnswer(
                        call -> {
                            loading.countDown();
                            release.await();
                            return snapshot(1);
                        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> cache.read("qb-java-001", 1, source));
            try {
                assertThat(loading.await(5, SECONDS)).isTrue();
                var second =
                        pool.submit(
                                () -> {
                                    TransactionSynchronizationManager.setActualTransactionActive(
                                            true);
                                    try {
                                        return cache.read("qb-java-001", 1, () -> snapshot(1));
                                    } finally {
                                        TransactionSynchronizationManager.clear();
                                    }
                                });
                assertThat(second.get(5, SECONDS)).isEqualTo(snapshot(1));
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, SECONDS)).isEqualTo(snapshot(1));
        }
    }

    @Test
    void damagedWrongVersionAndUnavailableCacheFallBack() {
        for (String value :
                List.of(
                        "bad json",
                        json.writeValueAsString(new QuestionSnapshotCache.Entry(2, snapshot(1))),
                        json.writeValueAsString(new QuestionSnapshotCache.Entry(1, snapshot(2))))) {
            when(values.get(anyString())).thenReturn(value);
            assertThat(cache.read("qb-java-001", 1, source)).isEqualTo(snapshot(1));
        }
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        doThrow(new IllegalStateException("offline"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.read("qb-java-001", 1, source)).isEqualTo(snapshot(1));
    }

    @Test
    void cachedArchiveNeverBypassesCurrentPublicationAndVersion() {
        var repository = mock(QuestionRepository.class);
        var users = mock(CurrentUserProvider.class);
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        var catalog = new DefaultQuestionCatalog(repository, users, provider);
        when(repository.state("qb-java-001"))
                .thenReturn(Optional.of(new QuestionRepository.State(2, true)));
        assertThatThrownBy(() -> catalog.requirePublished("qb-java-001", 1))
                .isInstanceOf(BusinessException.class);
        when(repository.state("qb-java-001"))
                .thenReturn(Optional.of(new QuestionRepository.State(1, false)));
        assertThatThrownBy(() -> catalog.requirePublished("qb-java-001", 1))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis);
        when(repository.state("qb-java-001"))
                .thenReturn(Optional.of(new QuestionRepository.State(2, true)));
        when(repository.snapshot("qb-java-001", 2)).thenReturn(Optional.of(snapshot(2)));
        assertThat(catalog.requirePublished("qb-java-001", 2)).isEqualTo(snapshot(2));
    }
}
