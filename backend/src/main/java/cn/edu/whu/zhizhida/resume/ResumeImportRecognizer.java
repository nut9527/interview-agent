package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeImportResult;

/** 将文档中已提取的文字识别为供用户核对的简历草稿。 */
@FunctionalInterface
interface ResumeImportRecognizer {
    ResumeImportResult recognize(String text, String fileName, String fallbackTargetRole);
}
