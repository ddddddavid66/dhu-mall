package com.yueji.mall.inventory.model;

import com.yueji.mall.common.api.ApiId;
import jakarta.validation.constraints.*;

public final class InventoryModels {
    private InventoryModels() {}
    public record InventoryView(ApiId skuId,long onHand,long reserved,long available,long version) {}
    public record Adjustment(@NotNull Integer delta,@NotBlank @Size(max=300) @Pattern(regexp="[^<>]*") String reason,
            @NotNull @Min(1) @Max(4294967295L) Long version) {}
}
