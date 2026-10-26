package cn.edu.whu.zhizhida.resume.api;

/** 返回当前用户已保存的简历快照，不存在与无权访问的简历统一返回 404。 */
public interface ResumeSnapshotReader {
    ResumeProfile requireOwned(Long resumeId);
}
