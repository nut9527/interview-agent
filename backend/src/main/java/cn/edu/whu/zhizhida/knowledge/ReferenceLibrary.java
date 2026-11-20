package cn.edu.whu.zhizhida.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReferenceLibrary {
    public record Summary(
            String id, String title, String topic, String sourceName, String sourceLabel) {
    }

    private record Entry(
            String id,
            String title,
            String topic,
            String sourceName,
            String sourceLabel,
            String resource) {
        Summary summary() {
            return new Summary(id, title, topic, sourceName, sourceLabel);
        }
    }

    private final List<Entry> entries;

    public ReferenceLibrary() throws IOException {
        try (var input = new ClassPathResource("reference-library/catalog.json").getInputStream()) {
            entries = List.of(new JsonMapper().readValue(input, Entry[].class));
        }
    }

    public List<Summary> list() {
        return entries.stream().map(Entry::summary).toList();
    }

    public KnowledgeImport preview(String id) throws IOException {
        var entry = entries.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("参考资料不存在"));
        String content = new ClassPathResource("reference-library/" + entry.resource())
                .getContentAsString(StandardCharsets.UTF_8);
        return new KnowledgeImport(entry.title(), entry.sourceName(), content, List.of());
    }
}
