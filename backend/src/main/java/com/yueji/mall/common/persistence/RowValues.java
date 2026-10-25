package com.yueji.mall.common.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class RowValues {
    private RowValues() {}
    public static Instant instant(Object value) {
        if (value==null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((LocalDateTime)value).toInstant(ZoneOffset.UTC);
    }
}
