package com.yueji.mall.trade.model;

import com.yueji.mall.common.api.*;
import com.yueji.mall.cart.model.AddressView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class TradeModels {
    private TradeModels() {}
    public record PreviewInput(@NotNull ApiId addressId,@NotNull @Size(min=1,max=50) List<@NotNull @Valid ApiId> cartItemIds) {}
    public record CreateOrderInput(@NotNull ApiId previewId) {}
    public record CancelInput(@NotBlank @Size(max=300) String reason) {}
    public enum OrderStatus {PENDING_PAYMENT,CLOSING,PAID,SHIPPED,COMPLETED,CANCELLED,CLOSED_REFUND}
    public record PreviewItem(ApiId skuId,String title,int quantity,ApiMoney unitPrice,ApiMoney lineAmount) {}
    public record PreviewShop(ApiId shopId,String shopName,List<PreviewItem> items,ApiMoney goodsAmount,ApiMoney freightAmount,ApiMoney payableAmount) {}
    public record PreviewView(ApiId previewId,Instant expiresAt,AddressView address,List<PreviewShop> shops,ApiMoney totalAmount) {}
    /** 仅用于数据库预览 JSON；版本和金额来自服务端，禁止接受客户端快照。 */
    public record ItemSnapshot(String cartItemId,Instant cartUpdatedAt,String skuId,int quantity,long skuVersion,long productVersion,ApiMoney price,String shopId) {}
    public record OrderSummary(ApiId id,String orderNo,ApiId shopId,String shopName,String status,String financialStatus,ApiMoney payableAmount,ApiMoney refundedAmount,Instant expiresAt,Instant createdAt,int itemCount) {}
    public record BatchView(ApiId batchId,String batchNo,ApiMoney totalAmount,List<OrderSummary> orders) {}
    public record OrderItemView(ApiId id,ApiId skuId,String bookTitle,String isbn,ApiMoney unitPrice,int quantity,ApiMoney lineAmount,int refundedQuantity,ApiMoney refundedAmount) {}
    public record Receiver(String name,String phone,String address) {}
    public record Timeline(String status,String action,Instant occurredAt,String reason) {}
    public record OrderView(ApiId id,String orderNo,ApiId shopId,String shopName,String status,String financialStatus,ApiMoney payableAmount,ApiMoney refundedAmount,Instant expiresAt,Instant createdAt,int itemCount,
            List<OrderItemView> items,Receiver receiver,Instant paidAt,Instant shippedAt,Instant completedAt,Instant closedAt,Object shipment,List<Timeline> timeline,long version) {}
}
