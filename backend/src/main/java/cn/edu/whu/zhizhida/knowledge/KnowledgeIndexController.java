package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.whu.zhizhida.common.ApiResponse;

@RestController
@RequestMapping("/api/knowledge/documents/{id}/index")
@ConditionalOnProperty(name = "agent.vector.enabled", havingValue = "true")
public class KnowledgeIndexController {
    private final KnowledgeIndexWorker worker;

    public KnowledgeIndexController(KnowledgeIndexWorker worker) {
        this.worker = worker;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> status(@PathVariable String id) {
        return ApiResponse.ok(worker.status(id));
    }

    @PostMapping("/rebuild")
    public ApiResponse<Void> rebuild(@PathVariable String id) {
        worker.rebuild(id);
        return ApiResponse.ok(null);
    }
}
