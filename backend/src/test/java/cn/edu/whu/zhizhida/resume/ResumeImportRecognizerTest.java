package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiResumeImportRecognizer.class);
    private Level previousLevel;

    @BeforeEach
    void enableImportLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreLogLevel() {
        logger.setLevel(previousLevel);
    }

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
                .recognize(text, "本地文件名.docx", "用户指定岗位").draft();
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
    @ExtendWith(OutputCaptureExtension.class)
    void upstreamFailureDoesNotBecomeASuccessfulRulesOnlyImport(CapturedOutput output) {
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.0), eq(8192)))
                .thenThrow(UpstreamServiceException.coded("MODEL_NETWORK"));
        var recognizer = config.resumeImportRecognizer(AiMode.MODEL, client, parser);
        assertThatThrownBy(() -> recognizer.recognize("姓名：王宁", "简历.docx", null))
                .isInstanceOf(UpstreamServiceException.class);
        assertThat(output.getAll()).contains("stage=model outcome=failure code=AI_NETWORK_ERROR")
                .doesNotContain("王宁", "简历.docx", "stage=validate");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unverifiableDraftReportsRecognitionErrorWithoutEchoingModelContent(CapturedOutput output) {
        var payload = new JsonMapper().readTree("{\"ownerName\":\"杜撰姓名\"}");
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.0), eq(8192))).thenReturn(payload);
        var recognizer = config.resumeImportRecognizer(AiMode.MODEL, client, parser);
        assertThatThrownBy(() -> recognizer.recognize("姓名：李明", "test.pdf", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原文或格式校验")
                .hasMessageNotContaining("杜撰姓名")
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo(ErrorCode.RESUME_IMPORT_VALIDATION_FAILED));
        assertThat(output.getAll()).contains("stage=model outcome=success",
                        "stage=validate outcome=failure field=ownerName reason=SEMANTIC_MISMATCH")
                .doesNotContain("杜撰姓名", "李明", "test.pdf");
    }

    @Test
    void offlineModeKeepsExistingRecognitionLocal() {
        var recognizer = config.resumeImportRecognizer(AiMode.MOCK, client, parser);
        var result = recognizer.recognize("姓名：王宁", "简历.docx", null);
        assertThat(result.draft().ownerName()).isEqualTo("王宁");
        assertThat(result.recognitionMode()).isEqualTo(cn.edu.whu.zhizhida.resume.api.ResumeImportResult.RecognitionMode.LOCAL);
        verifyNoInteractions(client);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void returnsPartialDraftAndLogsOnlyTheRejectedPathAndReason(CapturedOutput output) {
        when(client.completeJsonDirectly(anyString(), anyString(), eq(0.0), eq(8192)))
                .thenReturn(new JsonMapper().readTree("{\"ownerName\":\"杜撰姓名\",\"skills\":[\"Java\",\"虚构技能\"]}"));
        var result = config.resumeImportRecognizer(AiMode.MODEL, client, parser)
                .recognize("姓名：李明\nJava", "私密文件名.pdf", null);
        assertThat(result.draft().ownerName()).isEmpty();
        assertThat(result.draft().skills()).containsExactly("Java");
        assertThat(output.getAll()).contains("outcome=partial field=ownerName reason=SEMANTIC_MISMATCH",
                        "outcome=partial field=skills[1] reason=SEMANTIC_MISMATCH")
                .doesNotContain("李明", "杜撰姓名", "虚构技能", "私密文件名");
    }
}
