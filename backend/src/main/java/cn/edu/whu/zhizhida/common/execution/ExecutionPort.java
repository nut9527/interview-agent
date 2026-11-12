package cn.edu.whu.zhizhida.common.execution;

/** 可持久化的短时任务执行接口；等待用户操作属于业务状态，不应持续占用执行租约。 */
public interface ExecutionPort {
    /** owner 由可信服务端确定；requestId 在同一 owner 与 operation 下标识一次命令。 */
    record Command(long owner, String subjectType, String subjectId, long revision, String operation,
            String requestId, String input) { }
    record Run(String id, String status, String error) { }
    /** 租约代次用于识别当前执行权，阻止已过期的工作线程提交结果。 */
    record Context(String id, long owner, long epoch) { }
    /** 原子校验主体并受理；同键且主体类型、标识、修订号与输入一致时回放已有 Run，否则拒绝为冲突。 */
    Run accept(Command command);
    /** 仅查询指定 owner 的运行，不存在与归属不符均按资源不存在处理。 */
    Run read(long owner, String id);
    /** 模型调用前核对当前执行租约和任务截止时间。 */
    void requireActive(Context context);
    /** 在同一事务中复核租约、取消状态与截止时间，再执行 mutation 并提交运行终态；失败整体回滚。 */
    void complete(Context context, Runnable mutation);
}
