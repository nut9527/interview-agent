package cn.edu.whu.zhizhida.concurrency;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;
import cn.edu.whu.zhizhida.resume.ResumeTextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务层并发安全性测试 —— 验证核心服务在并发访问下的一致性。
 */
@DisplayName("服务层并发安全性")
class ServiceConcurrencyTest {

    private final ResumeTextParser textParser = new ResumeTextParser();

    // ═══════════════════════════════════════════════════════════
    // 并发岗位匹配
    // ═══════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════
    // 并发简历评分
    // ═══════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════
    // 并发文本解析
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("多线程并发解析简历文本 — 无状态冲突")
    void shouldHandleConcurrentTextParsing() throws Exception {
        String resumeText = """
                姓名：王小明
                求职意向：Java 后端开发工程师
                个人简介：熟悉服务端开发，重视测试与可维护性。
                专业技能：Java、Spring Boot、MySQL
                教育经历：2024-2028 武汉大学 软件工程
                """;

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<ResumeProfileRequest>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() ->
                    textParser.parse(resumeText, "resume.pdf", null)
            ));
        }

        for (Future<ResumeProfileRequest> future : futures) {
            ResumeProfileRequest result = future.get(10, TimeUnit.SECONDS);
            assertThat(result).isNotNull();
            assertThat(result.ownerName()).isEqualTo("王小明");
            assertThat(result.targetRole()).isEqualTo("Java 后端开发工程师");
            assertThat(result.skills()).containsExactly("Java", "Spring Boot", "MySQL");
        }
        executor.shutdown();
    }

    // ═══════════════════════════════════════════════════════════
    // 并发创建简历请求对象
    // ═══════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════


}
