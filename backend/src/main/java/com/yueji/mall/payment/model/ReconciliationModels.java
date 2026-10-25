package com.yueji.mall.payment.model;

import com.yueji.mall.common.api.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class ReconciliationModels {
    private ReconciliationModels() {}
    public record StartInput(@NotNull LocalDate billDate,@NotBlank @Pattern(regexp="ALIPAY_SANDBOX|LOCAL_MOCK") String provider) {}
    public record ReconciliationView(ApiId id,String provider,LocalDate billDate,String status,int checkedCount,int differenceCount,Instant completedAt) {}
    public record IssueResolutionInput(@NotBlank @Size(max=1000) String resolution,@NotBlank @Size(max=255) String evidenceReference) {}
    public record IssueView(ApiId id,String issueType,ApiId orderId,ApiId paymentId,ApiId refundId,ApiMoney expectedAmount,ApiMoney actualAmount,String status,String resolution,Instant createdAt) {}
}
