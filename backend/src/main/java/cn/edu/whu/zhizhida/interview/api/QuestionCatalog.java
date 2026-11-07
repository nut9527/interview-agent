package cn.edu.whu.zhizhida.interview.api;

/** 由面试模块定义的接口，题库模块提供只读实现。 */
public interface QuestionCatalog {
    default java.util.List<InterviewResources.Exercise> recommend(String competencyId, String query) { return java.util.List.of(); }
    /** 登录后读取系统已发布题目；不存在或下架时拒绝，expectedVersion 非空且已修订时报告冲突。 */
    QuestionSnapshot requirePublished(String id, Integer expectedVersion);
    /** 为没有 HTTP 安全上下文的可信后台任务读取系统已发布的参考题目。 */
    default QuestionSnapshot requirePublishedForExecution(long owner, String id, Integer expectedVersion) {
        return requirePublished(id,expectedVersion);
    }
    /** 持久化实现须在调用方事务内锁定发布状态并核对可选版本，失效时拒绝；默认实现仅用于兼容性校验，不提供锁。 */
    default void lockPublishedForExecution(long owner, String id, Integer version) {
        requirePublishedForExecution(owner, id, version);
    }
}
