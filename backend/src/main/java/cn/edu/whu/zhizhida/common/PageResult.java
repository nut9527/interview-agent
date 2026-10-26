package cn.edu.whu.zhizhida.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult { items = List.copyOf(items); }
    public PageResult(List<T> items, long total, PageRequest request) {
        this(items, total, request.page(), request.size());
    }
}
