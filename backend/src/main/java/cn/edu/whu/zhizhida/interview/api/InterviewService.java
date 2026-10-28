package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import java.util.List;

public interface InterviewService {
    PageResult<InterviewHistoryItem> listHistory(PageRequest page);


    List<InterviewHistoryItem> listHistory();

    InterviewSession findSession(String sessionId);

    PageResult<InterviewHistoryItem> listRetries(String sessionId, PageRequest page);

    InterviewSession start(InterviewStartRequest request);

    InterviewSession answer(String sessionId, InterviewAnswerRequest request);

    void deleteSession(String sessionId);
}
