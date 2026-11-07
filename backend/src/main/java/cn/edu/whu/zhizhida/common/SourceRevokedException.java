package cn.edu.whu.zhizhida.common;

/** 来源已被明确删除，后续自动重新生成不得复用其归档文本。 */
public final class SourceRevokedException extends BusinessException {
    public SourceRevokedException() {
        super(ErrorCode.INTERVIEW_STATE_CONFLICT, "本场引用的资料已删除，不能继续生成或沿用评价。回答记录保留，请更换材料新建训练。");
    }
}
