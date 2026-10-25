package com.yueji.mall.identity.model;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return false;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 12 && bytes <= 72;
    }
}
