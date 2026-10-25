package com.yueji.mall.trade.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.platform.service.OutboxService;
import com.yueji.mall.trade.repository.TradeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fulfillment-facing writes are kept in trade, the owner of the order state machine. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TradeFulfillmentAccess {
    private final SqlStore sql;
    private final TradeRepository orders;
    private final OutboxService outbox;

    public TradeFulfillmentAccess(SqlStore sql, TradeRepository orders, OutboxService outbox) {
        this.sql = sql;
        this.orders = orders;
        this.outbox = outbox;
    }

    public void shipped(String orderId, String shipmentId, String actorId, long expectedVersion, Instant shippedAt, String trace) {
        Map<String, Object> order = sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE", orderId);
        if (!"PAID".equals(order.get("status")) || ((Number) order.get("version")).longValue() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        if (sql.update("UPDATE trade_order SET status='SHIPPED',shipped_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PAID' AND version=?",
                Timestamp.from(shippedAt), orderId, expectedVersion) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        orders.log(orderId, "PAID", "SHIPPED", "SHIPMENT_CREATED", actorId, "整体发货", trace);
        outbox.fact("ShipmentCreated", orderId, Map.of("orderId", orderId, "shipmentId", shipmentId), trace);
    }

    public void received(String orderId, String shipmentId, String actorId, Instant receivedAt, String trace) {
        Map<String, Object> order = sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE", orderId);
        if (!"SHIPPED".equals(order.get("status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if (sql.update("UPDATE trade_order SET status='COMPLETED',completed_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='SHIPPED'",
                Timestamp.from(receivedAt), orderId) != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        orders.log(orderId, "SHIPPED", "COMPLETED", "MEMBER_RECEIPT", actorId, "会员确认收货", trace);
        outbox.fact("OrderCompleted", orderId, Map.of("orderId", orderId, "shipmentId", shipmentId, "completedAt", receivedAt.toString()), trace);
    }
}
