package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.BusinessException;

public class ResumeConflictException extends BusinessException {
    public ResumeConflictException() {
        super(ErrorCode.RESUME_REVISION_CONFLICT, "简历已被修改或删除，请保留当前编辑内容，刷新后再试。");
    }
}
