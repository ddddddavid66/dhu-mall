package com.yueji.mall.cart.model;

import com.yueji.mall.common.api.*;
import jakarta.validation.constraints.*;
import java.util.List;

public final class CartModels {
    private CartModels() {}
    public record CartSet(@NotNull @Min(1) @Max(99) Integer quantity,@NotNull Boolean selected) {}
    public record CartItemView(ApiId id,ApiId skuId,ApiId productId,String title,String coverUrl,ApiMoney salePrice,int quantity,
            boolean selected,long availableQuantity,boolean purchasable,String unavailableReason) {}
    public record CartGroupView(ApiId shopId,String shopName,List<CartItemView> items) {}
    public record CartView(List<CartGroupView> groups) {}
}
