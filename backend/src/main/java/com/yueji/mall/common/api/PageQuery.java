package com.yueji.mall.common.api;

public record PageQuery(Integer page, Integer pageSize, String sort) {
    public PageQuery {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page 必须为正整数，pageSize 必须在 1—100 之间");
        }
    }

    // 避免超大页码在计算偏移量时发生 int 溢出。
    public long offset() { return ((long) page - 1L) * pageSize; }
}
