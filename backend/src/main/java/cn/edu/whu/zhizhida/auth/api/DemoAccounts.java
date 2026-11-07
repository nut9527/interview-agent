package cn.edu.whu.zhizhida.auth.api;

import java.time.Instant;
import java.util.List;

/** 演示流程使用的身份管理接口，所有写操作均加入调用方事务。 */
public interface DemoAccounts {
    Long ensureTemplate();
    SandboxAccount createSandbox(int ttlHours, int maxActiveSandboxes);
    AuthResponse openSession(SandboxAccount sandbox);
    List<Long> lockExpiredIds(int batchSize);
    long countExpired();

    record SandboxAccount(Long userId, Long templateUserId, Instant expiresAt) {}
}
