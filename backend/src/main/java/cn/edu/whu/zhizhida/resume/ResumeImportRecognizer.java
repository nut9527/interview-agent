package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;

/** 将文档中已提取的文字识别为供用户核对的简历草稿。 */
@FunctionalInterface
interface ResumeImportRecognizer {
    ResumeProfileRequest recognize(String text, String fileName, String fallbackTargetRole);
}
