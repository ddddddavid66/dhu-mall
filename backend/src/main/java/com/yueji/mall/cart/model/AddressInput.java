package com.yueji.mall.cart.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddressInput(@NotBlank @Size(max=40) String receiverName,@NotBlank @Size(max=32) String phone,
        @NotBlank @Size(max=40) String province,@NotBlank @Size(max=40) String city,
        @NotBlank @Size(max=40) String district,@NotBlank @Size(max=200) String detail,@NotNull Boolean isDefault) {}
