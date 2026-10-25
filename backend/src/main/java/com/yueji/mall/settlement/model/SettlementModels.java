package com.yueji.mall.settlement.model;

import com.yueji.mall.common.api.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SettlementModels {
    private SettlementModels() {}
    public record SettlementInput(@NotNull ApiId shopId,@NotNull LocalDate periodDate) {}
    public record DecisionInput(@NotNull Boolean approved,@NotBlank @Size(max=500) String note,@NotNull @Min(1) Long version) {}
    public record PayoutInput(@NotNull @Min(1) Long version) {}
    public record AdjustmentInput(@NotNull ApiId originalSettlementId,@NotNull ApiId orderId,@NotNull ApiId issueId,@NotNull ApiMoney amount,@NotBlank @Size(max=500) String reason) {}
    public record AdjustmentDecisionInput(@NotBlank @Size(max=300) String note) {}
    public record ItemView(ApiId orderId,ApiMoney goodsAmount,ApiMoney refundAmount,ApiMoney netAmount,ApiMoney feeAmount,ApiMoney payableAmount) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdjustmentView(ApiId id,ApiMoney amount,String reason,String status,ApiId createdBy,ApiId approvedBy,ApiId targetSettlementId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettlementView(ApiId id,String settlementNo,ApiId shopId,LocalDate periodDate,String status,ApiMoney grossAmount,ApiMoney refundAmount,ApiMoney feeAmount,ApiMoney adjustmentAmount,ApiMoney payableAmount,ApiId createdBy,ApiId approvedBy,Instant paidAt,String mockPayoutNo,long version,List<ItemView> items,List<AdjustmentView> adjustments) {}
}
