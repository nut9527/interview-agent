package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewHistoryItem;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;

import java.util.Optional;

/** 以显式用户身份隔离会话；调用方负责身份可信性，写入还须满足来源授权与并发约束。 */
public interface InterviewSessionRepository {
    PageResult<InterviewHistoryItem> page(Long userId, PageRequest page);

    PageResult<InterviewHistoryItem> retries(Long userId, String sessionId, PageRequest page);


    InterviewSession save(Long userId, InterviewSession session);

    Optional<InterviewSession> findById(Long userId, String sessionId);

    /** 调用方须持有事务，行锁持续至事务结束；不存在或不属于该用户时返回空。 */
    Optional<InterviewSession> findByIdForUpdate(Long userId, String sessionId);

    boolean deleteById(Long userId, String sessionId);

    int deleteAllByUserId(Long userId);
}
