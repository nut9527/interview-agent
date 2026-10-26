package cn.edu.whu.zhizhida.resume.api;

import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历模块对外的应用接口，资源读写均基于当前登录用户的归属范围。
 * 正文与历史版本以公开快照传递，调用方无需访问数据库实体或 Mapper。
 */
public interface ResumeService {
    PageResult<ResumeVersionSummary> versions(Long resumeId, PageRequest page);


    List<ResumeProfile> list();

    ResumeProfile findById(Long id);

    ResumeProfile create(ResumeProfileRequest request);

    ResumeProfile update(Long id, ResumeProfileRequest request);

    ResumeProfile importDocument(MultipartFile file, String targetRole);

    /** 仅解析为待确认字段，不创建简历或历史版本。 */
    ResumeProfileRequest parseDocument(MultipartFile file, String targetRole);


    List<ResumeVersionView> versions(Long resumeId);

    ResumeVersionView version(Long resumeId, Long versionId);

    ResumeProfile restore(Long resumeId, Long versionId);

    /** 将历史内容保存为新版本；revision 非空时校验客户端看到的当前修订号。 */
    ResumeProfile restore(Long resumeId, Long versionId, Long revision);

    ResumeComparison compare(ResumeCompareRequest request);

    void delete(Long id);
}
