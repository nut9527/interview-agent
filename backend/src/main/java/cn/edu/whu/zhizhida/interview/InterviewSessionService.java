package cn.edu.whu.zhizhida.interview;

import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;

@Service
public class InterviewSessionService implements InterviewService {
    private final InterviewStateMachine sessions;

    InterviewSessionService(InterviewStateMachine sessions) {
        this.sessions = sessions;
    }

    public PageResult<InterviewHistoryItem> listHistory(PageRequest page) {
        return sessions.listHistory(page);
    }

    public InterviewSession findSession(String id) {
        return sessions.findSession(id);
    }

    public PageResult<InterviewHistoryItem> listRetries(String id, PageRequest page) {
        return sessions.listRetries(id, page);
    }

    public void deleteSession(String id) {
        sessions.deleteSession(id);
    }

}
