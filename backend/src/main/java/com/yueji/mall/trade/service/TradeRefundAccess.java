package com.yueji.mall.trade.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.platform.service.OutboxService;
import com.yueji.mall.trade.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Refund-facing writes are kept in trade; financial refund facts remain owned by payment. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TradeRefundAccess {
    private final SqlStore sql;
    private final TradeRepository orders;
    private final OutboxService outbox;

    public TradeRefundAccess(SqlStore sql, TradeRepository orders, OutboxService outbox) {
        this.sql = sql;
        this.orders = orders;
        this.outbox = outbox;
    }

    public void refunded(String orderId, String orderItemId, String afterSaleId, String refundId, int quantity,
                         BigDecimal amount, String actorId, String trace) {
        Map<String, Object> order = sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE", orderId);
        Map<String, Object> item = sql.required("SELECT * FROM order_item WHERE id=? AND order_id=? FOR UPDATE", orderItemId, orderId);
        BigDecimal expectedAmount = ((BigDecimal) item.get("unit_price")).multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal lineRefunded = (BigDecimal) item.get("refunded_amount");
        BigDecimal orderRefunded = (BigDecimal) order.get("refunded_amount");
        BigDecimal newLineAmount = lineRefunded.add(amount).setScale(2);
        BigDecimal newOrderAmount = orderRefunded.add(amount).setScale(2);
        long newQuantity = ((Number) item.get("refunded_quantity")).longValue() + quantity;
        if (quantity <= 0 || expectedAmount.compareTo(amount) != 0 || newLineAmount.compareTo((BigDecimal) item.get("line_amount")) > 0
                || newQuantity > ((Number) item.get("quantity")).longValue() || newOrderAmount.compareTo((BigDecimal) order.get("payable_amount")) > 0) {
            throw new BusinessException(ErrorCode.REFUND_LIMIT_EXCEEDED);
        }
        if (sql.update("UPDATE order_item SET refunded_quantity=refunded_quantity+?,refunded_amount=refunded_amount+?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                quantity, amount, orderItemId) != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        boolean fullyRefunded = sql.count("SELECT COUNT(*) FROM order_item WHERE order_id=? AND refunded_quantity<quantity", orderId) == 0;
        String financialStatus = newOrderAmount.compareTo((BigDecimal) order.get("payable_amount")) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        String oldStatus = order.get("status").toString();
        boolean closeUnshipped = fullyRefunded && "PAID".equals(oldStatus);
        if (closeUnshipped && sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=?", orderId) != 0) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String newStatus = closeUnshipped ? "CLOSED_REFUND" : oldStatus;
        if (sql.update("UPDATE trade_order SET refunded_amount=?,financial_status=?,status=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND refunded_amount=? AND status=?",
                newOrderAmount, financialStatus, newStatus, orderId, orderRefunded, oldStatus) != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (closeUnshipped) {
            orders.log(orderId, oldStatus, newStatus, "FULL_REFUND", actorId,
                    "未发货订单全部商品退款完成（refund " + refundId + ")", trace);
        }
        outbox.fact("RefundSucceeded", orderId, Map.of("orderId", orderId, "refundId", refundId, "afterSaleId", afterSaleId), trace);
    }
}
