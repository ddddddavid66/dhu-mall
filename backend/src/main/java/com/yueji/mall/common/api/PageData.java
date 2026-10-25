package com.yueji.mall.common.api;

import java.util.List;

public record PageData<T>(List<T> items, int page, int pageSize, long total) {
    public PageData {
        new PageQuery(page, pageSize, null);
        if (total < 0 || items.size() > pageSize || items.size() > total) {
            throw new IllegalArgumentException("分页结果与总数不一致");
        }
        items = List.copyOf(items);
    }
}
