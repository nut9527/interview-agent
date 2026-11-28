package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.UserInputException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeImportJobsTest {
        @TempDir
        Path directory;
        final KnowledgeImportRepository repository = mock(KnowledgeImportRepository.class);
        final KnowledgeDocumentImporter importer = mock(KnowledgeDocumentImporter.class);
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        final MockMultipartFile note = new MockMultipartFile("file", "note.md", "text/plain", new byte[] { 65 });
        KnowledgeImportExecutor jobs;

        void configure(String mode, boolean workerEnabled) {
                var env = new MockEnvironment()
                                .withProperty("knowledge.dispatch.mode", mode)
                                .withProperty(
                                                "knowledge.import.worker-enabled", Boolean.toString(workerEnabled))
                                .withProperty("knowledge.import.directory", directory.toString())
                                .withProperty("knowledge.import.shared-directory", directory.toString());
                jobs = new KnowledgeImportExecutor(repository, importer, new JsonMapper(), metrics, env);
        }

        @AfterEach
        void stop() {
                if (jobs != null)
                        jobs.close();
        }

        @Test
        void workerRoleDoesNotRecoverOrRemoveApiImportFiles() throws Exception {
                var sentinel = Files.createDirectory(directory.resolve("job-existing"));
                configure("local", false);
                jobs.initialize();
                jobs.checkLeases();
                assertThatExceptionOfType(BusinessException.class)
                                .isThrownBy(() -> jobs.submit(9L, note))
                                .extracting(BusinessException::status)
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                verifyNoInteractions(repository);
                assertThat(sentinel).exists();
        }

        @Test
        void localMaintenanceOnlyDeletesAbandonedJobDirectories() throws Exception {
                String activeId = UUID.randomUUID().toString(), expiredId = UUID.randomUUID().toString();
                var active = Files.createDirectory(directory.resolve("job-" + activeId));
                var expired = Files.createDirectory(directory.resolve("job-" + expiredId));
                var fresh = Files.createDirectory(directory.resolve("job-" + UUID.randomUUID()));
                var unrelated = Files.createDirectory(directory.resolve("other"));
                var old = FileTime.from(Instant.now().minusSeconds(60));
                for (var path : java.util.List.of(active, expired, unrelated))
                        Files.setLastModifiedTime(path, old);
                when(repository.processing(activeId)).thenReturn(true);
                when(repository.outstanding(9L)).thenReturn(3);
                configure("local", true);
                jobs.initialize();
                assertThat(expired).doesNotExist();
                assertThat(active).isDirectory();
                assertThat(fresh).isDirectory();
                assertThat(unrelated).isDirectory();
                assertThatExceptionOfType(BusinessException.class)
                                .isThrownBy(() -> jobs.submit(9L, note))
                                .extracting(BusinessException::status)
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                verify(repository, never()).insert(anyLong(), anyString(), anyString(), any());
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void sharedSubmissionKeepsTheUploadOnlyWhenTheCommitIsUncertain(boolean rejected)
                        throws Exception {
                configure("ROCKETMQ", false);
                RuntimeException failure = rejected
                                ? new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "full")
                                : new DataAccessResourceFailureException("commit outcome unknown");
                doThrow(failure)
                                .when(repository)
                                .enqueue(eq(9L), anyString(), eq("note.md"), anyString(), anyLong());
                assertThatThrownBy(() -> jobs.submit(9L, note)).isSameAs(failure);
                try (var paths = Files.list(directory)) {
                        var folders = paths.toList();
                        if (rejected)
                                assertThat(folders).isEmpty();
                        else {
                                assertThat(folders).hasSize(1);
                                assertThat(folders.getFirst().resolve("source.upload"))
                                                .hasBinaryContent(note.getBytes());
                                assertThat(folders.getFirst().resolve("upload.part")).doesNotExist();
                        }
                }
                verifyNoInteractions(importer);
        }

        @ParameterizedTest
        @ValueSource(strings = { "local", "rocketmq" })
        void interruptedUploadRemovesPartialFilesBeforeRegistration(String mode) throws Exception {
                configure(mode, true);
                var upload = spy(note);
                doAnswer(
                                call -> {
                                        Files.write(call.getArgument(0, Path.class), new byte[] { 42 });
                                        throw new java.io.IOException("private upload path");
                                })
                                .when(upload)
                                .transferTo(any(Path.class));
                assertThatExceptionOfType(BusinessException.class)
                                .isThrownBy(() -> jobs.submit(9L, upload))
                                .withMessage(mode.equals("local") ? "无法暂存上传文件，请重试。" : "无法持久保存上传文件，请重试。");
                assertThat(directory).isEmptyDirectory();
                verify(repository, never()).insert(anyLong(), anyString(), anyString(), any());
                verify(repository, never())
                                .enqueue(anyLong(), anyString(), anyString(), anyString(), anyLong());
        }

        @Test
        void aClaimCannotParseAnotherJobsSharedUpload() throws Exception {
                configure("rocketmq", true);
                String id = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
                var source = Files.createDirectory(directory.resolve(other)).resolve("source.upload");
                Files.write(source, note.getBytes());
                var claim = new KnowledgeImportRepository.Claim(
                                id,
                                9L,
                                "note.md",
                                other + "/source.upload",
                                "token",
                                Instant.now().plusSeconds(60));
                when(repository.claim(id)).thenReturn(Optional.of(claim));
                jobs.execute(id);
                verifyNoInteractions(importer);
                verify(repository).failed(eq(claim), anyString(), eq(true));
                verify(repository, never()).ready(any(), anyString());
                assertThat(source).hasBinaryContent(note.getBytes());
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void sharedLeaseLossStopsProgressAndCannotCommitADraft(boolean disconnected) throws Exception {
                configure("rocketmq", true);
                String id = UUID.randomUUID().toString();
                var source = Files.createDirectory(directory.resolve(id)).resolve("source.upload");
                Files.write(source, note.getBytes());
                var claim = new KnowledgeImportRepository.Claim(
                                id,
                                9L,
                                "note.md",
                                id + "/source.upload",
                                "token",
                                Instant.now().plusSeconds(60));
                when(repository.claim(id)).thenReturn(Optional.of(claim));
                var renew = when(repository.renew(claim)).thenReturn(true);
                if (disconnected)
                        renew.thenThrow(new DataAccessResourceFailureException("disconnected"));
                else
                        renew.thenReturn(false);
                when(importer.parseQueued(eq(source), eq("note.md"), any(), any()))
                                .thenAnswer(
                                                call -> {
                                                        BiConsumer<Integer, Integer> progress = call.getArgument(2);
                                                        jobs.checkLeases();
                                                        for (int page = 0; page <= 10; page++)
                                                                progress.accept(page, 12);
                                                        jobs.checkLeases();
                                                        progress.accept(12, 12);
                                                        throw new AssertionError(
                                                                        "stale executor continued after losing its lease");
                                                });
                jobs.execute(id);
                verify(repository).progress(claim, 0, 12);
                verify(repository).progress(claim, 10, 12);
                verify(repository, times(2)).progress(eq(claim), anyInt(), anyInt());
                verify(repository).failed(claim, "执行中断，等待重试。", true);
                verify(repository, never()).ready(any(), anyString());
                assertThat(source).exists();
        }

        static Stream<Arguments> failures() {
                String privateDetail = "/private/provider-token.json: private-material";
                return Stream.of(
                                Arguments.of(new IllegalArgumentException(privateDetail), "文件解析失败，请检查格式或拆分文件后重试。"),
                                Arguments.of(new CancellationException(privateDetail), "解析已取消或超时，请重新上传。"),
                                Arguments.of(
                                                new UserInputException(
                                                                "TXT / Markdown 文件须为有效 UTF-8 文本。",
                                                                new IllegalArgumentException(privateDetail)),
                                                "TXT / Markdown 文件须为有效 UTF-8 文本。"));
        }

        @ParameterizedTest
        @MethodSource("failures")
        void failedJobsOnlyPublishReviewedMessagesAndRemoveTheirTemporaryFiles(
                        RuntimeException failure, String expected) throws Exception {
                configure("local", true);
                when(repository.start(anyString())).thenReturn(true);
                when(repository.localClaim(anyString(), eq(9L), anyString(), any()))
                                .thenAnswer(
                                                call -> new KnowledgeImportRepository.Claim(
                                                                call.getArgument(0),
                                                                9L,
                                                                call.getArgument(2),
                                                                null,
                                                                "local",
                                                                call.getArgument(3)));
                when(importer.parse(any(Path.class), anyString(), any(), any())).thenThrow(failure);
                var stopped = new CountDownLatch(1);
                doAnswer(
                                call -> {
                                        stopped.countDown();
                                        return null;
                                })
                                .when(repository)
                                .stop(eq(9L), anyString(), eq("FAILED"), anyString());
                jobs.submit(9L, note);
                assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
                jobs.close();
                jobs = null;

                var messages = ArgumentCaptor.forClass(String.class);
                verify(repository, atLeastOnce())
                                .stop(eq(9L), anyString(), eq("FAILED"), messages.capture());
                assertThat(messages.getAllValues())
                                .contains(expected)
                                .allSatisfy(
                                                message -> assertThat(message).doesNotContain("private",
                                                                "provider-token"));
                verify(repository, never()).ready(any(), anyString());
                try (var files = Files.list(directory)) {
                        assertThat(files.toList()).isEmpty();
                }
        }
}
