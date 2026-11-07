package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeVersionView.ResumeVersionSummary;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import java.util.Optional;

public interface ResumeVersionRepository {
    PageResult<ResumeVersionSummary> page(Long userId, Long resumeId, PageRequest page);

    ResumeVersionView save(Long userId, ResumeProfile snapshot, String changeType, String changeNote);

    Optional<ResumeVersionView> findById(Long userId, Long resumeId, Long versionId);
}
