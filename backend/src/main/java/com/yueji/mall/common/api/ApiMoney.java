package com.yueji.mall.common.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record ApiMoney(BigDecimal value) {
    public static final String PATTERN = "^-?(0|[1-9][0-9]{0,9})\\.[0-9]{2}$";

    public ApiMoney {
        if (value == null || value.abs().compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException("金额超出 DECIMAL(12,2) 范围");
        }
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ApiMoney fromJson(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches(PATTERN)) {
            throw new IllegalArgumentException("金额必须为两位小数的十进制字符串");
        }
        // 负数只供财务调整使用；具体业务 DTO 在后续阶段追加非负/正数约束。
        return new ApiMoney(new BigDecimal(node.textValue()));
    }

    @JsonValue
    public String jsonValue() { return value.toPlainString(); }
}
