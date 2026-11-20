package cn.edu.whu.zhizhida.interview;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.interview.api.InterviewUserData;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DefaultInterviewUserData implements InterviewUserData {
    private final InterviewSessionRepository repository;
    private final ObjectProvider<InterviewRunStore> runs;

    public DefaultInterviewUserData(
            InterviewSessionRepository repository, ObjectProvider<InterviewRunStore> runs) {
        this.repository = repository;
        this.runs = runs;
    }

    @Override
    public void deleteOwnedData(Long userId) {
        runs.ifAvailable(store -> store.deleteOwned(userId));
        repository.deleteAllByUserId(userId);
    }
}
