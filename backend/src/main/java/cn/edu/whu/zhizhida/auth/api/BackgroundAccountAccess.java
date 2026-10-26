package cn.edu.whu.zhizhida.auth.api;

/** 用户编号由可信服务端提供，不接受模型指定；账号锁持有至事务结束。 */
public interface BackgroundAccountAccess { boolean lockActive(Long owner); }
