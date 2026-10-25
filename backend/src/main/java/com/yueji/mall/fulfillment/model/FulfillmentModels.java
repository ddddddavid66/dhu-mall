package com.yueji.mall.fulfillment.model;

import com.yueji.mall.common.api.ApiId;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class FulfillmentModels {
    private FulfillmentModels() {}
    public record ShipInput(@NotBlank @Size(max=50) String carrier,@NotBlank @Size(max=80) String trackingNo,@NotNull @Min(1) Long version) {}
    public record EventInput(@NotNull Instant eventTime,@NotBlank @Size(max=300) String description) {}
    public record ItemView(ApiId orderItemId,int shippedQuantity) {}
    public record EventView(Instant eventTime,String description,String source) {}
    public record ShipmentView(ApiId id,String carrier,String trackingNo,Instant shippedAt,Instant receivedAt,List<ItemView> items,List<EventView> events) {}
}
