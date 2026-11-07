package cn.edu.whu.zhizhida.resume.api;

import java.util.List;

/** 候选改写只引用本次请求中确实存在的条目，客户端核对后选择应用。 */
public record ResumeDraftOptimizeResponse(List<Change> changes) {
    public ResumeDraftOptimizeResponse { changes = List.copyOf(changes); }

    public record Change(int index, String text) {}
}
