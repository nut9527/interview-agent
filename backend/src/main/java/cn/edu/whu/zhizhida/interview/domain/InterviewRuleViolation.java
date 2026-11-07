package cn.edu.whu.zhizhida.interview.domain;

/** 表示业务规则冲突，由应用服务决定对应的 HTTP 响应。 */
public final class InterviewRuleViolation extends RuntimeException {
    public InterviewRuleViolation(String message) {
        super(message);
    }
}
