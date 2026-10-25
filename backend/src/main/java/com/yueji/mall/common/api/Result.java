package com.yueji.mall.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record Result<T>(String code, String message, T data, String traceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDetail> details) {
    public static <T> Result<T> ok(T data, String traceId) {
        return new Result<>("OK", "成功", data, traceId, List.of());
    }

    public static Result<Void> failure(ErrorCode code, String traceId) {
        return new Result<>(code.name(), code.message(), null, traceId, List.of());
    }

    public static Result<Void> failure(ErrorCode code, String traceId, List<FieldErrorDetail> details) {
        return new Result<>(code.name(), code.message(), null, traceId, List.copyOf(details));
    }
}
