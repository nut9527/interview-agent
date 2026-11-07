package cn.edu.whu.zhizhida.common;

/** 已固定引用的历史观察发生变化；这不代表当前面试材料也已失效。 */
public final class MemoryRevokedException extends BusinessException {
    public MemoryRevokedException() {
        super(ErrorCode.INTERVIEW_STATE_CONFLICT,"历史观察已纠正、删除或来源已撤销，本轮未提交。请核对记忆后重新提交。");
    }
}
