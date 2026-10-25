package com.yueji.mall.identity.model;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordValidator.class)
public @interface ValidPassword {
    String message() default "密码必须为 12—72 个 UTF-8 字节";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
