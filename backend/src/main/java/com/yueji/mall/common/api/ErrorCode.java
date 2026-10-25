package com.yueji.mall.common.api;

public enum ErrorCode {
    PARAM_INVALID(400, "请求参数不合法"),
    AUTH_REQUIRED(401, "请先登录"),
    TOKEN_EXPIRED(401, "登录已过期"),
    ACCESS_DENIED(403, "没有操作权限"),
    ACCOUNT_DISABLED(403, "账号已停用"),
    CSRF_INVALID(403, "跨站请求校验失败"),
    RESOURCE_NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "不支持该请求方法"),
    STATE_CONFLICT(409, "资源状态已变化"),
    VERSION_CONFLICT(409, "资源版本已变化"),
    OUT_OF_STOCK(409, "库存不足"),
    PRODUCT_UNAVAILABLE(409, "商品不可购买"),
    PRICE_CHANGED(409, "价格已变化，请重新确认"),
    IDEMPOTENCY_CONFLICT(409, "幂等键与原请求不一致"),
    REQUEST_IN_PROGRESS(409, "原请求仍在处理"),
    IDEMPOTENCY_KEY_EXPIRED(409, "幂等键重放窗口已过期"),
    REFUND_LIMIT_EXCEEDED(409, "超过可退款范围"),
    SETTLEMENT_BLOCKED(409, "尚不满足结算条件"),
    PREVIEW_EXPIRED(410, "结算预览已过期"),
    FILE_TOO_LARGE(413, "文件过大"),
    FILE_TYPE_UNSUPPORTED(415, "不支持的文件或媒体类型"),
    RATE_LIMITED(429, "请求过于频繁"),
    INTERNAL_ERROR(500, "服务暂时异常"),
    NOT_IMPLEMENTED(501, "该接口尚未实现"),
    CHANNEL_UNAVAILABLE(502, "支付渠道暂时不可用"),
    DEPENDENCY_UNAVAILABLE(503, "依赖服务暂时不可用");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() { return httpStatus; }
    public String message() { return message; }
}
