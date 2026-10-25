package com.yueji.mall.payment.model;

import com.yueji.mall.common.api.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentModels {
    private PaymentModels() {}
    public record PaymentAction(String type,String url,Instant expiresAt) {}
    public record PaymentView(ApiId id,String paymentNo,ApiId orderId,String provider,String status,ApiMoney amount,String currency,Instant paidAt,PaymentAction paymentAction) {}
    public record Accepted(PaymentView view,boolean created) {}
    public record Ticket(String id,String orderId,String paymentNo,String provider,BigDecimal amount,Instant expiresAt) {}
    public enum FactStatus { WAITING, PAID, CLOSED, NOT_FOUND, UNKNOWN }
    /** 只传经适配器核实的渠道事实，不传原始敏感正文。 */
    public record Fact(FactStatus status,String paymentNo,String tradeNo,BigDecimal amount,Instant paidAt) {
        public static Fact unknown(String no) {return new Fact(FactStatus.UNKNOWN,no,null,null,null);}
    }
    public enum RefundStatus { SUCCEEDED, FAILED, NOT_FOUND, UNKNOWN }
    public record RefundFact(RefundStatus status,String refundNo,String paymentNo,BigDecimal amount,String channelNo) {}
}
