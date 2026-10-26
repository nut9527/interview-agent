package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.api.TargetJobRequest;
import cn.edu.whu.zhizhida.jobs.domain.TargetJobRules;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.HashSet;
import java.util.UUID;

/**
 * 管理当前用户的私有 岗位说明 与可编辑岗位要求，通过修订号防止覆盖并发修改。
 * 提取与分析委托给纯规则；保存时验证要求引文确实来自当前 岗位说明，保持证据可追溯。
 */
@Service
public class TargetJobService implements cn.edu.whu.zhizhida.jobs.api.TargetJobSnapshotReader {
    private final TargetJobRepository repository;
    private final CurrentUserProvider users;
    private final ResumeSnapshotReader resumes;
    public TargetJobService(TargetJobRepository repository, CurrentUserProvider users, ResumeSnapshotReader resumes) {
        this.repository = repository; this.users = users; this.resumes = resumes;
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<TargetJob.Summary> page(PageRequest page) { return repository.page(users.requireUserId(), page); }
    public TargetJob find(String id) { return owned(users.requireUserId(), id); }
    public TargetJob.Extraction extract(String jdText) { users.requireUserId(); return TargetJobRules.extract(jdText); }

    @Transactional
    public TargetJob create(TargetJobRequest request) {
        Long userId = users.requireUserId();
        if (request.revision() != 0) throw new IllegalArgumentException("新目标岗位的 revision 必须为 0");
        TargetJob job = value(UUID.randomUUID().toString(), request);
        repository.insert(userId, job);
        return owned(userId, job.id());
    }
    @Transactional
    public TargetJob update(String id, TargetJobRequest request) {
        Long userId = users.requireUserId();
        owned(userId, id);
        if (!repository.update(userId, value(id, request))) throw conflict();
        return owned(userId, id);
    }
    @Transactional
    public void delete(String id, long revision) {
        Long userId = users.requireUserId();
        owned(userId, id);
        if (!repository.delete(userId, id, revision)) throw conflict();
    }
    public TargetJob.Analysis analyze(String id, TargetJob.AnalyzeRequest request) {
        TargetJob job = find(id);
        if (job.revision() != request.revision()) throw conflict();
        return TargetJobRules.analyze(job, resumes.requireOwned(request.resumeId()));
    }
    private TargetJob owned(Long userId, String id) {
        return repository.find(userId, id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "目标岗位不存在"));
    }
    private BusinessException conflict() { return new BusinessException(ErrorCode.TARGET_JOB_REVISION_CONFLICT, "目标岗位已变更，请重新打开最新版后核对修改。"); }
    private TargetJob value(String id, TargetJobRequest request) {
        if (request.deadline() != null && (request.deadline().getYear() < 1000 || request.deadline().getYear() > 9999)) {
            throw new IllegalArgumentException("截止日期年份须为 1000–9999");
        }
        String sourceUrl = clean(request.sourceUrl());
        if (!sourceUrl.isEmpty()) {
            URI uri = URI.create(sourceUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("来源链接必须为 HTTP/HTTPS 地址且不能包含凭据");
        }
        var ids = new HashSet<String>();
        for (var requirement : request.requirements()) {
            if (!ids.add(requirement.id())) throw new IllegalArgumentException("要求 ID 不能重复");
            if (requirement.sourceText() != null && !requirement.sourceText().isBlank()
                    && !request.jdText().contains(requirement.sourceText())) throw new IllegalArgumentException("要求引文不在当前 JD 中，请重新提取或清空该引文");
        }
        return new TargetJob(id, request.revision(), request.title().strip(), clean(request.company()), clean(request.sourceName()),
                sourceUrl, request.deadline(), request.jdText(), request.requirements(), null);
    }
    private String clean(String text) { return text == null ? "" : text.strip(); }
}
