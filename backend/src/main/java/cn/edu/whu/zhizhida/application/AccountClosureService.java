package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.AccountLifecycle;
import cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销的事务入口：校验注销资格，清理各模块业务数据，再删除身份与会话。
 * 任一步失败均回滚整个流程，避免账号和业务数据只删除一部分。
 */
@Service
public class AccountClosureService {
    private final AccountLifecycle accounts;
    private final UserDataCleanup cleanup;

    public AccountClosureService(AccountLifecycle accounts, UserDataCleanup cleanup) {
        this.accounts = accounts;
        this.cleanup = cleanup;
    }

    @Transactional
    public void deleteAccount(DeleteAccountRequest request) {
        Long userId = accounts.authorizeClosure(request);
        cleanup.delete(userId);
        accounts.deleteIdentity(userId);
    }
}
