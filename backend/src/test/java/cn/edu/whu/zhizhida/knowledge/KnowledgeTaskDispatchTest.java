package cn.edu.whu.zhizhida.knowledge;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeTaskDispatchTest {
    final KnowledgeImportRepository imports=mock(KnowledgeImportRepository.class);
    final KnowledgeImportExecutor executor=mock(KnowledgeImportExecutor.class);
    final KnowledgeIndexRepository indexes=mock(KnowledgeIndexRepository.class);
    final KnowledgeIndexWorker worker=mock(KnowledgeIndexWorker.class);
    final ObjectProvider<KnowledgeIndexWorker> workers=mock(ObjectProvider.class);
    final Channel channel=mock(Channel.class);
    final JsonMapper json=new JsonMapper();
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final String id=UUID.randomUUID().toString();

    KnowledgeTaskDispatch dispatch(boolean publisher,String role) {
        when(workers.getObject()).thenReturn(worker);
        return new KnowledgeTaskDispatch(imports,executor,indexes,workers,mock(RabbitTemplate.class),json,metrics,
                new MockEnvironment().withProperty("knowledge.dispatch.publisher-enabled",Boolean.toString(publisher))
                        .withProperty("app.runtime.role",role));
    }
    Message message(String kind,String task) {
        var properties=new MessageProperties();properties.setMessageId(kind+":"+task);properties.setDeliveryTag(9);
        return new Message(json.writeValueAsBytes(Map.of("id",task,"fileRef",task+"/source.upload")),properties);
    }
    void assertDeferred(String kind) throws Exception {
        verify(channel).basicAck(9,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        assertThat(metrics.get("knowledge.dispatch.delivery").tags("kind",kind,"outcome","deferred").counter().count()).isEqualTo(1);
    }
    @Test void failedImportDefersToPersistentNoticePublisher() throws Exception {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        dispatch(true,"all").importMessage(message("import",id),channel);
        assertDeferred("import");
    }
    @Test void splitWorkerReliesOnApiPublisherForImportRecovery() throws Exception {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        dispatch(false,"worker").importMessage(message("import",id),channel);
        assertDeferred("import");
    }
    @Test void failedIndexDefersToPersistentJobRecovery() throws Exception {
        when(worker.execute(12)).thenThrow(new IllegalStateException());
        dispatch(true,"all").indexMessage(message("index","12"),channel);
        assertDeferred("index");
    }
    @Test void disabledPublisherInCombinedRoleKeepsMessageForBrokerRetry() throws Exception {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        dispatch(false,"all").importMessage(message("import",id),channel);
        verify(channel).basicNack(9,false,true);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void interruptedIndexAdmissionRequeuesAndPreservesInterrupt() throws Exception {
        when(worker.execute(12)).thenThrow(new InterruptedException());
        try {
            dispatch(true,"all").indexMessage(message("index","12"),channel);
            verify(channel).basicNack(9,false,true);
            verify(channel,never()).basicAck(anyLong(),anyBoolean());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void interruptedImportFailureRequeuesWithoutDroppingInterrupt() throws Exception {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        var dispatch=dispatch(true,"all");
        try {
            Thread.currentThread().interrupt();
            dispatch.importMessage(message("import",id),channel);
            verify(channel).basicNack(9,false,true);
            verify(channel,never()).basicAck(anyLong(),anyBoolean());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void failedDeferredAckPropagatesForBrokerRecovery() throws Exception {
        doThrow(new IllegalStateException()).when(executor).execute(id);
        doThrow(new IOException("connection lost")).when(channel).basicAck(9,false);
        var dispatch=dispatch(true,"all");
        assertThatThrownBy(()->dispatch.importMessage(message("import",id),channel)).isInstanceOf(IOException.class);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void invalidPayloadStillGoesToDiagnosticQueue() throws Exception {
        dispatch(true,"all").importMessage(message("import","bad-id"),channel);
        verify(channel).basicReject(9,false);
        verifyNoInteractions(executor);
    }
}
