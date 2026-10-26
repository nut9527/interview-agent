package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;

import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository {
    PageResult<InterviewHistoryItem> page(Long userId, PageRequest page);


    InterviewSession save(Long userId, InterviewSession session);

    List<InterviewHistoryItem> findAll(Long userId);

    List<cn.edu.whu.zhizhida.interview.api.InterviewAgentState> recentAgentStates(Long userId, cn.edu.whu.zhizhida.interview.api.InterviewMode mode);

    Optional<InterviewSession> findById(Long userId, String sessionId);

    Optional<InterviewSession> findByIdForUpdate(Long userId, String sessionId);

    boolean tryAcquireAnswer(Long userId, String sessionId, int expectedTurn, String leaseToken, int leaseSeconds);

    boolean completeAnswer(Long userId, InterviewSession updated, int expectedTurn, String leaseToken);

    void releaseAnswer(Long userId, String sessionId, String leaseToken);

    boolean deleteById(Long userId, String sessionId);

    int deleteAllByUserId(Long userId);
}
