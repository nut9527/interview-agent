package cn.edu.whu.zhizhida.common.execution;

/** 按主体类型分发后台业务执行；命令受理时的归属与版本校验由 RunSubject 提供。 */
public interface RunExecutor {
    String subjectType();
    /** 使用服务端持久化的输入执行任务；业务写入须通过 ExecutionPort.complete 复核租约并提交。 */
    void execute(ExecutionPort.Context context, String subjectId, String operation, String input);
}
