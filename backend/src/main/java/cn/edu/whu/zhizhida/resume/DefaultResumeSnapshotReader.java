package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.springframework.stereotype.Service;

/** 向其他业务模块提供当前用户拥有的简历快照，统一按用户归属查询。 */
@Service
public class DefaultResumeSnapshotReader implements ResumeSnapshotReader {
    private final ResumeRepository resumes;
    private final CurrentUserProvider users;

    public DefaultResumeSnapshotReader(ResumeRepository resumes, CurrentUserProvider users) {
        this.resumes = resumes;
        this.users = users;
    }

    @Override
    public ResumeProfile requireOwned(Long resumeId) {
        return resumes.findById(users.requireUserId(), resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("resume not found: " + resumeId));
    }
}
