package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResumeImportRecognizerTest {
    private final JsonChatClient client = mock(JsonChatClient.class);
    private final ResumeAiConfiguration config = new ResumeAiConfiguration();
    private final ResumeTextParser parser = new ResumeTextParser();

    @Test
    void modelClassifiesTheDocumentWithoutSendingImportMetadata() {
        String text = "周宁 · 软件工程师\n随身工具箱：Git、Java";
        var result = new JsonMapper().readTree("""
                {"ownerName":"周宁","targetRole":"软件工程师","skills":["Git","Java"]}
                """);
        when(client.completeJsonDirectly(anyString(), eq(text), eq(0.0), eq(8192))).thenReturn(result);
        var draft = config.resumeImportRecognizer(AiMode.MODEL, client, parser)
                .recognize(text, "本地文件名.docx", "用户指定岗位");
        assertThat(draft.ownerName()).isEqualTo("周宁");
        assertThat(draft.targetRole()).isEqualTo("用户指定岗位");
        assertThat(draft.skills()).containsExactly("Git", "Java");
        verify(client).completeJsonDirectly(anyString(), eq(text), eq(0.0), eq(8192));
    }

    @Test
    void rejectsOversizedModelInputBeforeSubmittingIt() {
        var recognizer = config.resumeImportRecognizer(AiMode.MODEL, client, parser);
        assertThatThrownBy(() -> recognizer.recognize("文".repeat(32_001), "long.pdf", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("32000");
        verifyNoInteractions(client);
    }

    @Test
    void upstreamFailureDoesNotBecomeASuccessfulRulesOnlyImport() {
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.0), eq(8192)))
                .thenThrow(UpstreamServiceException.coded("MODEL_NETWORK"));
        var recognizer = config.resumeImportRecognizer(AiMode.MODEL, client, parser);
        assertThatThrownBy(() -> recognizer.recognize("姓名：王宁", "简历.docx", null))
                .isInstanceOf(UpstreamServiceException.class);
    }

    @Test
    void unverifiableDraftReportsRecognitionErrorWithoutEchoingModelContent() {
        var payload = new JsonMapper().readTree("{\"ownerName\":\"杜撰姓名\"}");
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.0), eq(8192))).thenReturn(payload);
        var recognizer = config.resumeImportRecognizer(AiMode.MODEL, client, parser);
        assertThatThrownBy(() -> recognizer.recognize("姓名：李明", "test.pdf", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原文或格式校验")
                .hasMessageNotContaining("杜撰姓名");
    }

    @Test
    void offlineModeKeepsExistingRecognitionLocal() {
        var recognizer = config.resumeImportRecognizer(AiMode.MOCK, client, parser);
        assertThat(recognizer.recognize("姓名：王宁", "简历.docx", null).ownerName()).isEqualTo("王宁");
        verifyNoInteractions(client);
    }
}
