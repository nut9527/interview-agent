package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;

final class InterviewMachineFixture {
    private InterviewMachineFixture() {}

    static InterviewStateMachine machine(InterviewSessionRepository sessions, CurrentUserProvider users,
            AiMode mode, ResumeSnapshotReader resumes) {
        return machine(sessions, users, mode, resumes, false);
    }

    static InterviewStateMachine machine(InterviewSessionRepository sessions, CurrentUserProvider users,
            AiMode mode, ResumeSnapshotReader resumes,
            boolean adaptive) {
        return new InterviewStateMachine(sessions, users, mode, resumes, null, null, null,
                null, null, new EvaluationEngine(), null, adaptive);
    }
}
