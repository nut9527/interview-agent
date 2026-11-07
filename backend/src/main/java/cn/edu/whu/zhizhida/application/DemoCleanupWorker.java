package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.AccountLifecycle;
import cn.edu.whu.zhizhida.auth.api.DemoAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoCleanupWorker {
    private final DemoAccounts demos;
    private final AccountLifecycle accounts;
    private final UserDataCleanup cleanup;
    private final int batchSize;

    public DemoCleanupWorker(DemoAccounts demos, AccountLifecycle accounts, UserDataCleanup cleanup,
                             @Value("${demo.cleanup.batch-size:25}") int batchSize) {
        this.demos = demos;
        this.accounts = accounts;
        this.cleanup = cleanup;
        this.batchSize = Math.min(100, Math.max(1, batchSize));
    }

    @Transactional
    public int runBatch() {
        var ids = demos.lockExpiredIds(batchSize);
        for (Long id : ids) {
            cleanup.delete(id);
            accounts.deleteIdentity(id);
        }
        return ids.size();
    }

    public long remaining() { return demos.countExpired(); }
}
