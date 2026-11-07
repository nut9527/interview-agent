package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.application.DemoCleanupWorker;
import cn.edu.whu.zhizhida.application.UserDataCleanup;
import cn.edu.whu.zhizhida.auth.api.AccountLifecycle;
import cn.edu.whu.zhizhida.auth.api.DemoAccounts;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DemoCleanupWorkerTest {
    @Test
    void deletesOnlyLockedExpiredBatchAndCanRepeat() {
        DemoAccounts demos = mock(DemoAccounts.class);
        AccountLifecycle accounts = mock(AccountLifecycle.class);
        UserDataCleanup cleanup = mock(UserDataCleanup.class);
        when(demos.lockExpiredIds(2)).thenReturn(List.of(11L, 12L), List.of());
        DemoCleanupWorker worker = new DemoCleanupWorker(demos, accounts, cleanup, 2);
        assertThat(worker.runBatch()).isEqualTo(2);
        assertThat(worker.runBatch()).isZero();
        var order = inOrder(cleanup, accounts);
        order.verify(cleanup).delete(11L);
        order.verify(accounts).deleteIdentity(11L);
        order.verify(cleanup).delete(12L);
        order.verify(accounts).deleteIdentity(12L);
        verify(accounts, never()).deleteIdentity(13L);
    }
}
