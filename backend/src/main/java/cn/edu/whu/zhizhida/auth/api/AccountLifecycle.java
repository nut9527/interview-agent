package cn.edu.whu.zhizhida.auth.api;

/** 供可信应用编排使用的账号生命周期接口，删除操作必须加入已有事务。 */
public interface AccountLifecycle {
    Long authorizeClosure(DeleteAccountRequest request);
    void deleteIdentity(Long userId);
}
