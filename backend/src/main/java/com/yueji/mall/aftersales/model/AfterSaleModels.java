package com.yueji.mall.aftersales.model;

import com.yueji.mall.common.api.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AfterSaleModels {
    private AfterSaleModels() {}
    public record ApplyInput(@NotNull ApiId orderItemId,@NotBlank @Pattern(regexp="REFUND_ONLY|RETURN_REFUND") String type,@Min(1) @Max(99) int quantity,@NotBlank @Size(max=500) String reason,List<@NotNull ApiId> evidenceFileIds) {}
    public record DecisionInput(@NotNull Boolean approved,@NotBlank @Size(max=500) String note) {}
    public record ReturnInput(@NotBlank @Size(max=50) String carrier,@NotBlank @Size(max=80) String trackingNo) {}
    public record InspectionInput(@NotNull Boolean accepted,@NotNull Boolean restock,@NotBlank @Size(max=500) String note) {}
    public record RefundView(ApiId id,String refundNo,ApiId afterSaleId,ApiId paymentId,String reasonType,String status,ApiMoney amount,String failureCode,Instant succeededAt) {}
    public record AfterSaleView(ApiId id,String afterSaleNo,ApiId orderId,ApiId orderItemId,String type,int quantity,ApiMoney requestedAmount,String reason,String status,List<ApiId> evidenceFileIds,String reviewNote,Instant returnDeadline,String returnCarrier,String returnTrackingNo,int restockQuantity,RefundView refund,Instant createdAt) {}
    public record OrderSnapshot(ApiId id,String orderNo,String status,String financialStatus,ApiMoney payableAmount,ApiMoney refundedAmount,List<LineSnapshot> items,String receiverName,String receiverPhone,String receiverAddress) {}
    public record LineSnapshot(ApiId id,String bookTitle,String isbn,ApiMoney unitPrice,int quantity,int refundedQuantity,ApiMoney refundedAmount) {}
}
