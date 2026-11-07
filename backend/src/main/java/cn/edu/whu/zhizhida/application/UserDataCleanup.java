package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.interview.api.InterviewUserData;
import cn.edu.whu.zhizhida.jobs.api.JobUserData;
import cn.edu.whu.zhizhida.resume.api.ResumeUserData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在调用方事务内先清理面试、岗位与简历；其余关联数据在随后删除账号身份时由外键级联清理。 */
@Service
public class UserDataCleanup {
    private final InterviewUserData interviews;
    private final JobUserData jobs;
    private final ResumeUserData resumes;
    public UserDataCleanup(InterviewUserData interviews, JobUserData jobs, ResumeUserData resumes) {
        this.interviews = interviews; this.jobs = jobs; this.resumes = resumes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(Long userId) {
        interviews.deleteOwnedData(userId);
        jobs.deleteOwnedData(userId);
        resumes.deleteOwnedData(userId);
    }
}
