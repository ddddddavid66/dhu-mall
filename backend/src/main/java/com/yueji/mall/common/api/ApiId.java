package com.yueji.mall.common.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;

public record ApiId(@JsonValue String value) {
    private static final BigInteger MAX = new BigInteger("18446744073709551615");

    public ApiId {
        if (value == null || !value.matches("[1-9][0-9]{0,19}")
                || new BigInteger(value).compareTo(MAX) > 0) {
            throw new IllegalArgumentException("ID 必须为正的无符号 BIGINT 十进制字符串");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ApiId fromJson(JsonNode node) {
        // 不允许 Jackson 把 JSON 数字悄悄转换成字符串，防止浏览器精度损失。
        if (!node.isTextual()) { throw new IllegalArgumentException("ID 必须使用字符串传输"); }
        return new ApiId(node.textValue());
    }
}
