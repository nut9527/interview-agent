package cn.edu.whu.zhizhida.jobs.api;

public interface JobUserData {
    /** 仅供创建新演示空间的事务调用，初始化该访客独有的岗位 JD。 */
    void initializeDemoJobs(Long sandboxUserId);
    void deleteOwnedData(Long userId);
}
