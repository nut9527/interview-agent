package cn.edu.whu.zhizhida.learning.api;

/** 由可信业务调用方传入账号；记录与来源完成、失效操作共用调用方事务，事件只包含标识和版本。 */
public interface ObservationJournal {
    /** 登记待消费事件，同一账号、来源与版本重复登记不会产生新事件。 */
    void record(long owner, String sessionId, String version);
    /** 来源删除时使画像快照失效；读取或对账仍需复核来源，不以此通知替代访问校验。 */
    void sourceRemoved(long owner, String sessionId);
}
