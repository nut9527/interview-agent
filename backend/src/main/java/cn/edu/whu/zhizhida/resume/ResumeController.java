package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeComparison;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeService;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历 HTTP 入口：负责参数绑定、格式校验和统一响应，业务规则由 ResumeService 执行。
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @GetMapping
    public ApiResponse<List<ResumeProfile>> list() {
        return ApiResponse.ok(resumeService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeProfile> detail(@PathVariable Long id) {
        return ApiResponse.ok(resumeService.findById(id));
    }

    @PostMapping
    public ApiResponse<ResumeProfile> create(@Valid @RequestBody ResumeProfileRequest request) {
        return ApiResponse.ok(resumeService.create(request));
    }

    @PostMapping("/import")
    public ApiResponse<ResumeProfile> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetRole", required = false) String targetRole) {
        return ApiResponse.ok(resumeService.importDocument(file, targetRole));
    }

    /** 仅解析并返回待确认字段；客户端核对后通过创建接口保存正文和初始版本。 */
    @PostMapping("/import/parse")
    public ApiResponse<ResumeProfileRequest> parseDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetRole", required = false) String targetRole) {
        return ApiResponse.ok(resumeService.parseDocument(file, targetRole));
    }

    @PostMapping("/compare")
    public ApiResponse<ResumeComparison> compare(@Valid @RequestBody ResumeCompareRequest request) {
        return ApiResponse.ok(resumeService.compare(request));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<PageResult<ResumeVersionSummary>> versions(@PathVariable Long id, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(resumeService.versions(id, new PageRequest(page, size)));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ApiResponse<ResumeVersionView> version(@PathVariable Long id, @PathVariable Long versionId) {
        return ApiResponse.ok(resumeService.version(id, versionId));
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public ApiResponse<ResumeProfile> restore(@PathVariable Long id, @PathVariable Long versionId,
                                             @Valid @RequestBody(required = false) ResumeRestoreRequest request) {
        return ApiResponse.ok(resumeService.restore(id, versionId, request == null ? null : request.revision()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResumeProfile> update(@PathVariable Long id, @Valid @RequestBody ResumeProfileRequest request) {
        return ApiResponse.ok(resumeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return ApiResponse.ok(null);
    }
}
