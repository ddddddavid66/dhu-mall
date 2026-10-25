package com.yueji.mall.trade.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.inventory.service.ReservationService;
import com.yueji.mall.platform.service.OutboxService;
import com.yueji.mall.trade.repository.TradeRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Transactional(propagation=Propagation.MANDATORY)
public class TradePaymentAccess {
    private final SqlStore sql;private final ReservationService stock;private final TradeRepository orders;private final OutboxService outbox;
    public TradePaymentAccess(SqlStore sql,ReservationService stock,TradeRepository orders,OutboxService outbox) {this.sql=sql;this.stock=stock;this.orders=orders;this.outbox=outbox;}
    public Map<String,Object> lock(String id) {return sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE",id);}
    public void lockResources(String id) {stock.lockOrderResources(id);}
    public void paid(Map<String,Object> order,String payment,Instant paidAt,String trace) {
        String id=order.get("id").toString(),from=order.get("status").toString();
        if(!List.of("PENDING_PAYMENT","CLOSING").contains(from)) {
            // 终态仅记录财务事实；绝不恢复物流状态或重新扣库存。
            if("UNPAID".equals(order.get("financial_status"))) sql.update("UPDATE trade_order SET financial_status='PAID',paid_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",Timestamp.from(paidAt),id);
            return;
        }
        stock.finish(id,true,trace);
        if(sql.update("UPDATE trade_order SET status='PAID',financial_status='PAID',paid_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status=?",Timestamp.from(paidAt),id,from)!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        orders.log(id,from,"PAID","PAYMENT_VERIFIED",null,"渠道支付已核实",trace);outbox.fact("OrderPaid",id,Map.of("orderId",id,"paymentId",payment),trace);
    }
    public void closed(Map<String,Object> order,String trace) {
        String id=order.get("id").toString(),from=order.get("status").toString();
        if("PENDING_PAYMENT".equals(from)) {
            sql.update("UPDATE trade_order SET status='CLOSING',close_reason='渠道确认关闭',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PENDING_PAYMENT'",id);
            orders.log(id,from,"CLOSING","CHANNEL_CLOSE_REQUEST",null,"渠道确认关闭",trace);from="CLOSING";
        }
        if(!"CLOSING".equals(from)) return;
        stock.finish(id,false,trace);
        if(sql.update("UPDATE trade_order SET status='CANCELLED',closed_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='CLOSING'",id)!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        String reason=Objects.toString(order.get("close_reason"),"渠道已确认关闭");
        orders.log(id,from,"CANCELLED","CLOSE_VERIFIED",null,reason,trace);outbox.fact("OrderClosed",id,Map.of("orderId",id,"reason",reason),trace);
    }
    public void compensationRefunded(String order,String refund,BigDecimal amount,String trace) {
        sql.update("UPDATE trade_order SET financial_status='REFUNDED',refunded_amount=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",amount,order);
        outbox.fact("RefundSucceeded",order,Map.of("orderId",order,"refundId",refund),trace);
    }
}
