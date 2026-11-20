package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Create;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Edit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Rename;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Restore;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Summary;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping("/documents")
    public ApiResponse<PageResult<Summary>> page(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.page(new PageRequest(page, size)));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocument> find(@PathVariable String id) {
        return ApiResponse.ok(service.find(id));
    }

    @PostMapping("/documents")
    public ApiResponse<KnowledgeDocument> create(@Valid @RequestBody Create request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocument> rename(@PathVariable String id, @Valid @RequestBody Rename request) {
        return ApiResponse.ok(service.rename(id, request));
    }

    @PutMapping("/documents/{id}/content")
    public ApiResponse<KnowledgeDocument> edit(@PathVariable String id, @Valid @RequestBody Edit request) {
        return ApiResponse.ok(service.edit(id, request));
    }

    @GetMapping("/documents/{id}/versions")
    public ApiResponse<PageResult<Summary>> versions(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.versions(id, new PageRequest(page, size)));
    }

    @GetMapping("/documents/{id}/versions/{version}")
    public ApiResponse<KnowledgeDocument> version(@PathVariable String id, @PathVariable int version) {
        return ApiResponse.ok(service.version(id, version));
    }

    @PostMapping("/documents/{id}/versions/{version}/restore")
    public ApiResponse<KnowledgeDocument> restore(@PathVariable String id, @PathVariable int version,
            @Valid @RequestBody Restore request) {
        return ApiResponse.ok(service.restore(id, version, request));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/search")
    public ApiResponse<List<Hit>> search(@RequestParam String query,
            @RequestParam(required = false) List<String> documentIds) {
        return ApiResponse.ok(service.search(query, documentIds == null ? List.of() : documentIds, 30));
    }
}
