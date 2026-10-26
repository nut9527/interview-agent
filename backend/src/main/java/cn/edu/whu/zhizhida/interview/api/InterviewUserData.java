package cn.edu.whu.zhizhida.interview.api;

/** 供可信应用清理流程使用的接口，所有操作均加入调用方事务。 */
public interface InterviewUserData {
    void deleteOwnedData(Long userId);
}
