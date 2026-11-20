package cn.edu.whu.zhizhida.auth.api;

/**
 * 在调用方已有事务内锁定有效账号，锁持有至事务结束；无账号或演示沙箱已过期时返回 false。
 * 用户编号由可信服务端提供，不接受模型指定；未开启事务时拒绝调用。
 */
public interface BackgroundAccountAccess {
    boolean lockActive(Long owner);
}
