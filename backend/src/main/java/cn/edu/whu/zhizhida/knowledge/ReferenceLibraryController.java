package cn.edu.whu.zhizhida.knowledge;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge/library")
public class ReferenceLibraryController {
    private final ReferenceLibrary library;
    private final KnowledgeService service;

    public ReferenceLibraryController(ReferenceLibrary library, KnowledgeService service) {
        this.library = library;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ReferenceLibrary.Summary>> list() {
        return ApiResponse.ok(library.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeImport> preview(@PathVariable String id) throws IOException {
        return ApiResponse.ok(library.preview(id));
    }

    @PostMapping("/{id}/import")
    public ApiResponse<KnowledgeDocument> importDocument(
            @PathVariable String id, @Valid @RequestBody KnowledgeImport.Confirm request)
            throws IOException {
        var source = library.preview(id);
        return ApiResponse.ok(
                service.confirmImport(
                        new KnowledgeImport.Confirm(
                                request.title(),
                                source.sourceName(),
                                request.content(),
                                request.confirmed())));
    }
}
