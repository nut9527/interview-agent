package cn.edu.whu.zhizhida.learning.api;

/** 按最坏情况预占预算；调用失败或外部执行结果未知时仍保留预占额度。 */
public interface MissionBudget {
    /** 在调用方事务中锁定本人目标；目标不存在或归属不符时按不存在处理。 */
    void lock(long owner, String goalId);
    /** 同一目标与 Run 的重放不重复预占；首次预占须满足目标有效期、启用状态及所有预算上限，否则拒绝。 */
    void reserve(long owner, String goalId, String runId, boolean training, int modelAttempts, int tools);
}
