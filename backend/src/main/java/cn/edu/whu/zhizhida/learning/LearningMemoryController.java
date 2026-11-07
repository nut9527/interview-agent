package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.learning.api.LearningMemory;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning")
public class LearningMemoryController {
    private final LearningMemoryService service;
    public LearningMemoryController(LearningMemoryService service) { this.service=service; }
    @GetMapping("/observations") public ApiResponse<LearningMemory> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.read(new PageRequest(page,size)));
    }
    @PutMapping("/preferences") public ApiResponse<Preferences> preferences(@Valid @RequestBody PreferenceUpdate input) { return ApiResponse.ok(service.preferences(input)); }
    @PostMapping("/observations/{id}/corrections") public ApiResponse<Observation> correct(@PathVariable String id,@Valid @RequestBody Correction input) { return ApiResponse.ok(service.correct(id,input)); }
    @PostMapping("/observations/{id}/delete") public ApiResponse<Void> remove(@PathVariable String id,@Valid @RequestBody Removal input) { service.remove(id,input);return ApiResponse.ok(null); }
    @GetMapping("/observations/export") public ApiResponse<LearningMemory.Export> export(@RequestParam(defaultValue="") String cursor) { return ApiResponse.ok(service.export(cursor)); }
}
