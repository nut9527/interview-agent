package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeComparison;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeFieldDiff;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeReference;
import cn.edu.whu.zhizhida.resume.api.ResumeService;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 简历应用服务，负责当前用户的正文维护、文档导入、版本快照与历史恢复。
 * 所有资源访问均限定用户归属；会改变正文的入口同时保存版本，保持事务一致性。
 */
@Service
public class DefaultResumeService implements ResumeService {

    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeDocumentImporter documentImporter;
    private final CurrentUserProvider currentUserProvider;

    public DefaultResumeService(ResumeRepository resumeRepository,
                                ResumeVersionRepository versionRepository,
                                ResumeDocumentImporter documentImporter,
                                CurrentUserProvider currentUserProvider) {
        this.resumeRepository = resumeRepository;
        this.versionRepository = versionRepository;
        this.documentImporter = documentImporter;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public List<ResumeProfile> list() {
        return resumeRepository.findAll(currentUserId());
    }

    @Override
    public ResumeProfile findById(Long id) {
        return resumeRepository.findById(currentUserId(), id)
                .orElseThrow(() -> resumeNotFound(id));
    }

    @Override
    @Transactional
    public ResumeProfile create(ResumeProfileRequest request) {
        return saveNew(request, "CREATED", "手动创建简历");
    }

    @Override
    @Transactional
    public ResumeProfile update(Long id, ResumeProfileRequest request) {
        Long userId = currentUserId();
        ResumeProfile current = resumeRepository.findById(userId, id).orElseThrow(() -> resumeNotFound(id));
        // 先检查客户端修订号；仓储 仍需按读取到的修订号条件更新，防止检查后的并发覆盖。
        requireRevision(current, request.revision());
        ResumeProfile saved = resumeRepository.save(userId, buildProfile(id, request)
                .withDetails(request.details() == null ? current.details() : request.details())
                .withRevision(current.revision()));
        // 正文与快照共用当前事务，版本写入失败时正文也必须回滚。
        versionRepository.save(userId, saved, "UPDATED", "编辑并保存简历");
        return saved;
    }

    @Override
    public ResumeProfileRequest parseDocument(MultipartFile file, String targetRole) {
        currentUserId();
        return documentImporter.importDocument(file, targetRole);
    }

    @Override
    @Transactional
    public ResumeProfile importDocument(MultipartFile file, String targetRole) {
        ResumeProfileRequest parsed = documentImporter.importDocument(file, targetRole);
        String fileName = file.getOriginalFilename() == null ? "外部文档" : file.getOriginalFilename();
        return saveNew(parsed, "IMPORTED", "从 " + shorten(fileName, 180) + " 导入");
    }

    @Override
    public List<ResumeVersionView> versions(Long resumeId) {
        Long userId = currentUserId();
        resumeRepository.findById(userId, resumeId).orElseThrow(() -> resumeNotFound(resumeId));
        return versionRepository.findAll(userId, resumeId);
    }

    @Override
    @Transactional
    public ResumeProfile restore(Long resumeId, Long versionId) {
        return restore(resumeId, versionId, null);
    }

    @Override
    @Transactional
    public ResumeProfile restore(Long resumeId, Long versionId, Long revision) {
        Long userId = currentUserId();
        ResumeProfile current = resumeRepository.findById(userId, resumeId).orElseThrow(() -> resumeNotFound(resumeId));
        requireRevision(current, revision);
        ResumeVersionView version = versionRepository.findById(userId, resumeId, versionId)
                .orElseThrow(() -> new ResourceNotFoundException("resume version not found: " + versionId));
        // 恢复作为一次新的修改提交：沿用当前修订号做并发校验，并追加 RESTORED 版本。
        ResumeProfile restored = withId(version.snapshot(), resumeId).withRevision(current.revision());
        ResumeProfile saved = resumeRepository.save(userId, restored);
        versionRepository.save(userId, saved, "RESTORED", "恢复至版本 V" + version.versionNumber());
        return saved;
    }

    @Override
    public ResumeComparison compare(ResumeCompareRequest request) {
        Long userId = currentUserId();
        ResumeVersionView left = resolveReference(userId, request.left());
        ResumeVersionView right = resolveReference(userId, request.right());
        List<ResumeFieldDiff> fields = compareProfiles(left.snapshot(), right.snapshot());
        int changed = (int) fields.stream().filter(ResumeFieldDiff::changed).count();
        return new ResumeComparison(left, right, changed, fields);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long userId = currentUserId();
        if (!resumeRepository.deleteById(userId, id)) {
            throw resumeNotFound(id);
        }
    }

    private ResumeProfile buildProfile(Long id, ResumeProfileRequest request) {
        return new ResumeProfile(
                id,
                request.ownerName(),
                request.targetRole(),
                request.summary(),
                values(request.skills()),
                values(request.educationExperiences()),
                values(request.projectExperiences()),
                values(request.internshipExperiences()),
                values(request.certificates()),
                request.templateKey() == null || request.templateKey().isBlank() ? "classic" : request.templateKey(), 0L, request.details()
        );
    }

    private ResumeProfile saveNew(ResumeProfileRequest request, String changeType, String changeNote) {
        Long userId = currentUserId();
        ResumeProfile saved = resumeRepository.save(userId, buildProfile(null, request));
        versionRepository.save(userId, saved, changeType, changeNote);
        return saved;
    }

    private ResumeVersionView resolveReference(Long userId, ResumeReference reference) {
        ResumeProfile current = resumeRepository.findById(userId, reference.resumeId())
                .orElseThrow(() -> resumeNotFound(reference.resumeId()));
        if (reference.versionId() == null) {
            return new ResumeVersionView(null, reference.resumeId(), 0, "CURRENT", "当前保存版本", null, current);
        }
        return versionRepository.findById(userId, reference.resumeId(), reference.versionId())
                .orElseThrow(() -> new ResourceNotFoundException("resume version not found: " + reference.versionId()));
    }

    private ResumeProfile withId(ResumeProfile source, Long id) {
        return new ResumeProfile(id, source.ownerName(), source.targetRole(), source.summary(), source.skills(),
                source.educationExperiences(), source.projectExperiences(), source.internshipExperiences(),
                source.certificates(), source.templateKey(), source.revision(), source.details());
    }

    private List<ResumeFieldDiff> compareProfiles(ResumeProfile left, ResumeProfile right) {
        List<ResumeFieldDiff> fields = new ArrayList<>();
        fields.add(diff("ownerName", "姓名", List.of(left.ownerName()), List.of(right.ownerName())));
        fields.add(diff("targetRole", "目标岗位", List.of(left.targetRole()), List.of(right.targetRole())));
        fields.add(diff("summary", "个人简介", scalar(left.summary()), scalar(right.summary())));
        fields.add(diff("skills", "技能", left.skills(), right.skills()));
        fields.add(diff("educationExperiences", "教育经历", left.educationExperiences(), right.educationExperiences()));
        fields.add(diff("projectExperiences", "项目经历", left.projectExperiences(), right.projectExperiences()));
        fields.add(diff("internshipExperiences", "实习经历", left.internshipExperiences(), right.internshipExperiences()));
        fields.add(diff("certificates", "证书", left.certificates(), right.certificates()));
        fields.add(diff("templateKey", "简历模板", List.of(left.templateKey()), List.of(right.templateKey())));
        fields.add(diff("details", "联系方式、教育、项目与实习安排", detailLines(left.details()), detailLines(right.details())));
        return List.copyOf(fields);
    }

    private ResumeFieldDiff diff(String field, String label, List<String> leftValues, List<String> rightValues) {
        List<String> safeLeft = leftValues == null ? List.of() : leftValues;
        List<String> safeRight = rightValues == null ? List.of() : rightValues;
        Set<String> leftSet = new LinkedHashSet<>(safeLeft);
        Set<String> rightSet = new LinkedHashSet<>(safeRight);
        List<String> added = rightSet.stream().filter(value -> !leftSet.contains(value)).toList();
        List<String> removed = leftSet.stream().filter(value -> !rightSet.contains(value)).toList();
        // 增删按值集合展示，列表整体比较决定是否变更，因此仅调整顺序也会标为变更。
        return new ResumeFieldDiff(field, label, safeLeft, safeRight, added, removed, !safeLeft.equals(safeRight));
    }

    private List<String> scalar(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private String shorten(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private List<String> values(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private List<String> cleanValues(List<String> source) {
        return source == null ? List.of() : source.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Long currentUserId() {
        return currentUserProvider.requireUserId();
    }

    private List<String> detailLines(ResumeDetails details) {
        List<String> lines = new ArrayList<>();
        if (details.contact() != null) {
            var contact = details.contact();
            if (contact.phone() != null) lines.add("电话：" + contact.phone());
            if (contact.email() != null) lines.add("邮箱：" + contact.email());
            if (contact.location() != null) lines.add("所在城市：" + contact.location());
            contact.links().forEach(link -> lines.add(link.label() + "：" + link.url()));
        }
        details.education().forEach(e -> lines.add("教育：" + String.join(" | ",
                e.school(), e.major(), e.degree(), e.startDate(), e.endDate())));
        if (details.graduationDate() != null) lines.add("毕业时间：" + details.graduationDate());
        if (details.availability() != null) lines.add("实习安排：" + details.availability());
        details.projects().forEach(p -> lines.add(String.join(" | ", trimToEmpty(p.name()),
                trimToEmpty(p.role()), trimToEmpty(p.period()), trimToEmpty(p.background()),
                String.join("、", p.techStack()), String.join("；", values(p.contributions())))));
        return lines;
    }

    private void requireRevision(ResumeProfile current, Long expected) {
        if (expected != null && !expected.equals(current.revision())) {
            throw new ResumeConflictException();
        }
    }

    private ResourceNotFoundException resumeNotFound(Long id) {
        return new ResourceNotFoundException("resume not found: " + id);
    }
    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ResumeVersionSummary> versions(Long resumeId, PageRequest page) {
        resumeRepository.findById(currentUserId(), resumeId).orElseThrow(() -> resumeNotFound(resumeId));
        return versionRepository.page(currentUserId(), resumeId, page);
    }

    @Override
    public ResumeVersionView version(Long resumeId, Long versionId) {
        return versionRepository.findById(currentUserId(), resumeId, versionId)
                .orElseThrow(() -> new cn.edu.whu.zhizhida.common.ResourceNotFoundException("resume version not found"));
    }

}
