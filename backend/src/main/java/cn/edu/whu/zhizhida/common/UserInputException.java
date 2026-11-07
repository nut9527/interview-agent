package cn.edu.whu.zhizhida.common;

/**
 * 面向用户的输入校验失败。消息必须是经过审查的固定提示，不能拼接原始输入、
 * 模型输出、内部路径或上游异常消息；cause 仅供内部诊断，不进入 HTTP 响应。
 */
public final class UserInputException extends IllegalArgumentException {
    public UserInputException(String message) {
        super(message);
    }

    public UserInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
