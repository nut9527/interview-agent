package cn.edu.whu.zhizhida.interview.api;

/** 会话业务状态，与持久 Run 的执行阶段分开管理。 */
public enum InterviewState {
    CREATED,
    ASKING,
    WAITING_ANSWER,
    FEEDBACK,
    FINISHED
}
