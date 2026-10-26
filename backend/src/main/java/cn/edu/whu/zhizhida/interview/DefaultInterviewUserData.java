package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewUserData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DefaultInterviewUserData implements InterviewUserData {
    private final InterviewSessionRepository repository;

    public DefaultInterviewUserData(InterviewSessionRepository repository) { this.repository = repository; }

    @Override
    public void deleteOwnedData(Long userId) { repository.deleteAllByUserId(userId); }

}
