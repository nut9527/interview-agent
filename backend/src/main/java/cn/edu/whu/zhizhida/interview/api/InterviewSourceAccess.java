package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 后台执行者显式传入身份；最终授权校验持有来源共享锁，直至调用方事务提交。 */
public interface InterviewSourceAccess {
    /** 返回已删除或不属于 owner 的资料标识；仅检查身份，不要求历史快照与当前版本一致。 */
    List<String> unavailable(long owner, List<String> documentIds);
    /** 须在调用方事务内使用，任一资料无访问资格即拒绝；共享锁阻止提交前发生并发删除。 */
    void lockForUse(long owner, List<String> documentIds);
    /** 须在调用方事务内校验本人资料的当前版本与内容哈希；不匹配时报告冲突，要求重新确认来源。 */
    void requireCurrentVersion(long owner, String documentId, int version, String sha256);
}
