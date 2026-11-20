package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImportJob;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge/imports/jobs")
public class KnowledgeImportController {
    private final KnowledgeImportJobs jobs;

    public KnowledgeImportController(KnowledgeImportJobs jobs) {
        this.jobs = jobs;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<KnowledgeImportJob> submit(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(jobs.submit(file));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeImportJob>> list() {
        return ApiResponse.ok(jobs.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeImportJob> find(@PathVariable String id) {
        return ApiResponse.ok(jobs.find(id));
    }

    @GetMapping("/{id}/result")
    public ApiResponse<KnowledgeImport> result(@PathVariable String id) {
        return ApiResponse.ok(jobs.result(id));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<KnowledgeDocument> confirm(@PathVariable String id,
            @Valid @RequestBody KnowledgeImport.Confirm request) {
        return ApiResponse.ok(jobs.confirm(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@PathVariable String id) {
        jobs.cancel(id);
        return ApiResponse.ok(null);
    }
}
