package cn.edu.whu.zhizhida.common.execution;

/** 主体访问仅用于事务，具体实现不得依赖执行或提交服务。 */
public interface RunSubject {
    String subjectType();
    /** 在调用方事务内按归属锁定主体；无权访问与不存在均按资源不存在处理。 */
    void lock(long owner, String subjectId);
    /** 新命令受理前检查操作、修订号和主体状态；历史命令回放不重复执行此校验。 */
    void validate(long owner, String subjectId, long revision, String operation);
}
