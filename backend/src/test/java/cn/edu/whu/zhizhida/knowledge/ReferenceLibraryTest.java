package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class ReferenceLibraryTest {
    @Test
    void previewsEveryBundledDocumentWithoutChangingItsText() throws Exception {
        var library = new ReferenceLibrary();
        var manifest =
                new JsonMapper()
                        .readTree(
                                new ClassPathResource("reference-library/catalog.json")
                                        .getInputStream());
        assertThat(library.list()).hasSize(27);
        for (var item : manifest) {
            var preview = library.preview(item.path("id").asText());
            String original =
                    new ClassPathResource("reference-library/" + item.path("resource").asText())
                            .getContentAsString(StandardCharsets.UTF_8);
            assertThat(preview.content()).isEqualTo(original);
            assertThat(preview.sourceName()).isEqualTo(item.path("sourceName").asText());
            assertThat(preview.content()).isNotBlank();
        }
        assertThat(new JsonMapper().writeValueAsString(library.list()))
                .doesNotContain("D:/", "D:\\", "resource");
    }

    @Test
    void importsReviewedEditsWithTheCatalogSource() throws Exception {
        var library = new ReferenceLibrary();
        var service = mock(KnowledgeService.class);
        var source = library.list().getFirst();
        var request = new KnowledgeImport.Confirm("核对后的标题", "客户端改写的来源", "核对后的原文", true);
        new ReferenceLibraryController(library, service).importDocument(source.id(), request);
        verify(service)
                .confirmImport(
                        new KnowledgeImport.Confirm(
                                request.title(), source.sourceName(), request.content(), true));
    }

    @Test
    void unknownReferenceCannotBeImported() throws Exception {
        var library = new ReferenceLibrary();
        assertThatThrownBy(() -> library.preview("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
