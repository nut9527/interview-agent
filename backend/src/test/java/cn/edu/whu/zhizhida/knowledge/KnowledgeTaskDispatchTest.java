package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeTaskDispatchTest {
    final KnowledgeImportRepository imports = mock(KnowledgeImportRepository.class);
    final KnowledgeImportExecutor executor = mock(KnowledgeImportExecutor.class);
    final KnowledgeIndexRepository indexes = mock(KnowledgeIndexRepository.class);
    final KnowledgeIndexWorker worker = mock(KnowledgeIndexWorker.class);
    final ObjectProvider<KnowledgeIndexWorker> workers = mock(ObjectProvider.class);
    final RocketTransport transport = mock(RocketTransport.class);
    final JsonMapper json = new JsonMapper();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final String id = UUID.randomUUID().toString();
    final MockEnvironment env = new MockEnvironment();

    KnowledgeTaskDispatch dispatch(boolean publisher, String role) {
        when(workers.getObject()).thenReturn(worker);
        return new KnowledgeTaskDispatch(
                imports,
                executor,
                indexes,
                workers,
                transport,
                json,
                metrics,
                env.withProperty(
                        "knowledge.dispatch.publisher-enabled", Boolean.toString(publisher))
                        .withProperty("app.runtime.role", role));
    }

    RocketTransport.Delivery message(String kind, String keyId, String task) {
        return new RocketTransport.Delivery(
                "test-knowledge-" + kind,
                "msg",
                List.of(kind + ":" + keyId),
                json.writeValueAsBytes(Map.of("id", task, "fileRef", task + "/source.upload")));
    }

    @Test
    void splitWorkerReliesOnApiPublisherForImportRecovery() {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        assertThat(dispatch(false, "worker").receive("import", message("import", id, id))).isTrue();
        assertThat(
                metrics.get("knowledge.dispatch.delivery")
                        .tags("kind", "import", "outcome", "deferred")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void failedIndexDefersToPersistentJobRecovery() throws Exception {
        when(worker.execute(12)).thenThrow(new IllegalStateException());
        assertThat(dispatch(true, "all").receive("index", message("index", "12", "12"))).isTrue();
        assertThat(
                metrics.get("knowledge.dispatch.delivery")
                        .tags("kind", "index", "outcome", "deferred")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void disabledPublisherInCombinedRoleLeavesMessageForBrokerRetry() {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        assertThat(dispatch(false, "all").receive("import", message("import", id, id))).isFalse();
    }

    @Test
    void interruptedIndexAdmissionPreservesInterruptAndLeavesMessageForRetry() throws Exception {
        when(worker.execute(12)).thenThrow(new InterruptedException());
        try {
            assertThat(dispatch(true, "all").receive("index", message("index", "12", "12")))
                    .isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrongKeyAndMalformedBodyNeedConfirmedQuarantine() {
        var dispatch = dispatch(true, "all");
        var wrongKey = message("import", "wrong", id);
        assertThat(dispatch.receive("import", wrongKey)).isTrue();
        verify(transport).quarantine(wrongKey, "business-key");
        var malformed = new RocketTransport.Delivery(
                "test-knowledge-import", "msg", List.of(), new byte[] { '{' });
        doThrow(new IllegalStateException("unavailable"))
                .when(transport)
                .quarantine(malformed, "invalid-json");
        assertThatThrownBy(() -> dispatch.receive("import", malformed)).hasMessage("unavailable");
        verifyNoInteractions(executor);
    }

    @Test
    void apiRegistersNoConsumerAndVectorFlagGatesTheIndexWorker() {
        env.withProperty("knowledge.import.worker-enabled", "false")
                .withProperty("agent.vector.enabled", "true")
                .withProperty("agent.vector.worker-enabled", "false");
        dispatch(true, "api").startConsumers();
        verifyNoInteractions(transport);
        env.withProperty("knowledge.import.worker-enabled", "true")
                .withProperty("agent.vector.enabled", "false")
                .withProperty("agent.vector.worker-enabled", "true");
        dispatch(false, "worker").startConsumers();
        verify(transport)
                .listen(eq("import-workers"), eq(List.of("knowledge-import")), eq(2), any());
        verifyNoMoreInteractions(transport);
    }
}
