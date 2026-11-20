package cn.edu.whu.zhizhida.knowledge.api;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 解析过程不写入资料库；只有用户明确确认的正文才成为引用来源。 */
public record KnowledgeImport(String title, String sourceName, String content, List<String> warnings) {
    public record Confirm(@NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 255) String sourceName,
            @NotBlank @Size(max = 2000000) String content,
            @NotNull @AssertTrue(message = "请先核对正文，再确认入库。") Boolean confirmed) {
    }
}
