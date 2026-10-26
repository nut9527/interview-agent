package cn.edu.whu.zhizhida.boundary;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;
import cn.edu.whu.zhizhida.resume.ResumeTextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 极端输入场景测试 —— 验证系统在异常/恶意/极限输入下的健壮性。
 */
@DisplayName("极端输入场景")
class ExtremeInputTest {

    private final ResumeTextParser textParser = new ResumeTextParser();

    // ═══════════════════════════════════════════════════════════
    // 场景 1： SQL 注入式技能标签
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景1: SQL 注入式技能标签")
    class SqlInjectionInSkills {




    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2： XSS 脚本注入
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景2: XSS 脚本注入")
    class XssInjection {



        @Test
        @DisplayName("简历文本解析器安全处理 XSS")
        void shouldParseXssTextSafely() {
            String xssText = "姓名：<script>alert(1)</script>\n技能：<img onerror=alert(1) src=x>";

            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse(xssText, "resume.pdf", null)
            );

            assertThat(result.ownerName()).isEqualTo("<script>alert(1)</script>");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3： 超长文本 >100KB
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景3: 超长文本输入")
    class UltraLongText {

        @Test
        @DisplayName("100KB+ 简历文本不 OOM 不超时")
        void shouldHandleVeryLongResumeText() {
            String longLine = "项目经历：负责某大型系统开发，使用了Java、Spring Boot、MySQL等技术栈，完成了接口设计和联调测试，性能优化效果显著。";
            StringBuilder sb = new StringBuilder("姓名：测试用户\n目标岗位：Java后端开发\n技能：Java, Spring Boot\n");
            // 生成约 150KB 文本
            for (int i = 0; i < 1000; i++) {
                sb.append(longLine).append(" 第").append(i).append("个项目。\n");
            }

            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse(sb.toString(), "long.pdf", null)
            );

            assertThat(result.ownerName()).isEqualTo("测试用户");
            assertThat(result.targetRole()).isEqualTo("Java后端开发");
        }


    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：全字段空值级联
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景4: 全字段 null 级联")
    class NullCascade {





        @Test
        @DisplayName("null fileName 在文本解析器中不 NPE")
        void shouldNotThrowNpeOnNullFileName() {
            // “教育背景”属于段落标题，不应被推断为姓名。
            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse("教育背景\n武汉大学 计算机科学", null, null)
            );

            assertThat(result.ownerName()).isEqualTo("待补充姓名");
            assertThat(result.targetRole()).isEqualTo("待补充目标岗位");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 5： Unicode 特殊字符
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景5: Unicode 特殊字符")
    class UnicodeSpecialCharacters {

        @Test
        @DisplayName("Emoji 和零宽字符不导致崩溃")
        void shouldHandleEmojiAndZeroWidthChars() {
            String textWithEmoji = """
                    姓名：张😀三
                    技能：Java​、Spring‌Boot、‍React
                    个人简介：热爱编程💻，有3年经验 。""";

            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse(textWithEmoji, "emoji.pdf", null)
            );

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("RTL override 字符不导致崩溃")
        void shouldHandleRtlOverrideChars() {
            String rtlText = "姓名：‮Test‬用户\n技能：Java, Python";

            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse(rtlText, "rtl.pdf", null)
            );

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("全角半角混合和特殊标点正确解析")
        void shouldHandleFullWidthAndSpecialPunctuation() {
            String mixedText = """
                    姓名：Ｔｅｓｔ
                    技能：Ｊａｖａ、Ｓｐｒｉｎｇ　Ｂｏｏｔ；Python|Django""";

            ResumeProfileRequest result = assertDoesNotThrow(() ->
                    textParser.parse(mixedText, "mixed.pdf", null)
            );

            assertThat(result).isNotNull();
            assertThat(result.skills()).isNotEmpty();
        }
    }
}
