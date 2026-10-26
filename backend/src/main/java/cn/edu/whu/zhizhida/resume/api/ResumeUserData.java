package cn.edu.whu.zhizhida.resume.api;

/** 供可信应用流程使用；初始化或复制演示数据时，调用方须持有事务与演示容量锁。 */
public interface ResumeUserData {
    void initializeDemoTemplate(Long templateUserId);
    void copyDemoResumes(Long templateUserId, Long sandboxUserId);
    void deleteOwnedData(Long userId);
}
