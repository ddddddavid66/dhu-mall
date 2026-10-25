package com.yueji.mall.payment.adapter;

import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;

public interface PaymentAdapter {
    PaymentAction action(Ticket ticket);
    Fact query(Ticket ticket);
    Fact close(Ticket ticket);
    RefundFact refund(Ticket ticket,String refundNo,BigDecimal amount);
    RefundFact queryRefund(Ticket ticket,String refundNo,BigDecimal amount);
}
