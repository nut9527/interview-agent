package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.application.DemoCleanupWorker;
import cn.edu.whu.zhizhida.application.DemoSandboxService;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.resume.ResumeRepository;
import cn.edu.whu.zhizhida.resume.ResumeVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class DemoCapacityIntegrationIT extends IntegrationTestBase {
    @Autowired UserAccountRepository users;
    @Autowired SessionRepository sessions;
    @Autowired ResumeRepository resumes;
    @Autowired ResumeVersionRepository versions;
    @Autowired cn.edu.whu.zhizhida.auth.api.DemoAccounts demos;
    @Autowired cn.edu.whu.zhizhida.auth.api.AccountLifecycle accounts;
    @Autowired cn.edu.whu.zhizhida.resume.api.ResumeUserData resumeData;
    @Autowired cn.edu.whu.zhizhida.application.UserDataCleanup cleanupData;
    @Autowired PasswordEncoder passwords;
    @Autowired InterviewSessionRepository interviews;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void separateServiceInstancesShareCapacityAndCleanupOnlyExpiredSandboxes() throws Exception {
        int capacity = Math.toIntExact(users.countActiveDemoSandboxes()) + 2;
        var first = new DemoSandboxService(demos, resumeData, 4, capacity, true);
        var second = new DemoSandboxService(demos, resumeData, 4, capacity, true);
        var transaction = new TransactionTemplate(transactions);
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        List<Long> accepted = new ArrayList<>();
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 6; i++) {
                var service = i % 2 == 0 ? first : second;
                tasks.add(pool.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try { return transaction.execute(status -> service.demoLogin().user().id()); }
                    catch (BusinessException ex) {
                        assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        return null;
                    }
                }));
            }
            start.countDown();
            for (var task : tasks) { Long id = task.get(20, TimeUnit.SECONDS); if (id != null) accepted.add(id); }
            assertThat(accepted).hasSize(2);
            assertThat(users.countActiveDemoSandboxes()).isEqualTo(capacity);
            assertThat(resumes.findAll(accepted.get(0))).isNotEmpty();
            jdbc.update("UPDATE app_users SET demo_expires_at = '1971-01-01 00:00:00' WHERE id = ?", accepted.get(0));
            var cleanup = new DemoCleanupWorker(demos, accounts, cleanupData, 1);
            Integer removed = transaction.execute(status -> cleanup.runBatch());
            assertThat(removed).isEqualTo(1);
            assertThat(users.findById(accepted.get(0))).isEmpty();
            assertThat(resumes.findAll(accepted.get(0))).isEmpty();
            assertThat(users.findById(accepted.get(1))).isPresent();
            assertThat(users.findByUsername("demo")).isPresent();
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
