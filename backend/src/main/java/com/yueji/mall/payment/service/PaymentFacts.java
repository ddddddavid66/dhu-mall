package com.yueji.mall.payment.service;

import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.trade.service.TradePaymentAccess;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

/** 同一订单锁串行化查单、回调、关单及补偿；未知事实不改变库存。 */
@Service
public class PaymentFacts {
    private final SqlStore sql;private final DatabaseTransaction transactions;private final TradePaymentAccess trade;private final PaymentRepository repository;private final Clock clock;
    public PaymentFacts(SqlStore sql,DatabaseTransaction transactions,TradePaymentAccess trade,PaymentRepository repository,Clock clock) {this.sql=sql;this.transactions=transactions;this.trade=trade;this.repository=repository;this.clock=clock;}
    public boolean apply(String id,Fact fact,String eventKey,String digest,String trace) {
        var original=repository.row(id);
        return transactions.run(()->{
            var order=trade.lock(text(original,"order_id"));trade.lockResources(text(order,"id"));
            var payment=sql.required("SELECT * FROM payment WHERE id=? FOR UPDATE",id);String no=text(payment,"payment_no");
            String event=sql.one("SELECT id FROM payment_event WHERE provider=? AND event_key=?",payment.get("provider"),eventKey).map(r->text(r,"id")).orElse(null);
            if(event!=null && sql.count("SELECT COUNT(*) FROM payment_event WHERE id=? AND processing_status='PROCESSED'",event)>0) {
                if(sql.count("SELECT COUNT(*) FROM payment_event WHERE id=? AND payload_digest=?",event,digest)>0) return true;
                repository.issue("event:"+Crypto.hash(eventKey+digest),"EVENT_KEY_MISMATCH",text(order,"id"),id,(BigDecimal)payment.get("amount"),fact.amount());return false;
            }
            if(event==null) event=sql.insert("INSERT INTO payment_event(payment_id,provider,event_key,event_type,verified,payload_digest,trace_id) VALUES(?,?,?,'CHANNEL_FACT',1,?,?)",id,payment.get("provider"),eventKey,digest,trace);
            boolean matches=no.equals(fact.paymentNo()) && (fact.amount()==null || ((BigDecimal)payment.get("amount")).compareTo(fact.amount())==0)
                    && (payment.get("channel_trade_no")==null || fact.tradeNo()==null || payment.get("channel_trade_no").equals(fact.tradeNo()));
            if(!matches || fact.status()==FactStatus.PAID && (fact.amount()==null || fact.tradeNo()==null || fact.tradeNo().isBlank())) {
                repository.issue("fact:"+Crypto.hash(payment.get("provider")+":"+eventKey),"PAYMENT_MISMATCH",text(order,"id"),id,(BigDecimal)payment.get("amount"),fact.amount());
                sql.update("UPDATE payment_event SET processing_status='REJECTED',processed_at=UTC_TIMESTAMP(3) WHERE id=?",event);return false;
            }
            if(fact.status()==FactStatus.PAID) paid(order,payment,fact,trace);
            else if(fact.status()==FactStatus.CLOSED && !"SUCCEEDED".equals(payment.get("status"))) {
                sql.update("UPDATE payment SET status='CLOSED',failure_code=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);trade.closed(order,trace);
            }
            sql.update("UPDATE payment_event SET processing_status='PROCESSED',processed_at=UTC_TIMESTAMP(3) WHERE id=?",event);return true;
        });
    }
    private void paid(Map<String,Object> order,Map<String,Object> payment,Fact fact,String trace) {
        if("SUCCEEDED".equals(payment.get("status"))) return;
        String id=text(payment,"id"),orderId=text(order,"id");var when=fact.paidAt()==null?clock.instant():fact.paidAt();
        sql.update("UPDATE payment SET status='SUCCEEDED',channel_trade_no=?,paid_at=?,failure_code=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",fact.tradeNo(),Timestamp.from(when),id);
        trade.paid(order,id,when,trace);
        if(List.of("CANCELLED","CLOSED_REFUND").contains(text(order,"status"))) {
            repository.issue("late:"+id,"LATE_PAYMENT",orderId,id,(BigDecimal)payment.get("amount"),fact.amount());
            // 唯一(payment_id,迟到补偿类型)在数据库兜底；不得为重复成功通知另建退款。
            if(sql.count("SELECT COUNT(*) FROM refund WHERE payment_id=? AND reason_type='LATE_PAYMENT_COMPENSATION'",id)==0)
                sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?)","R"+UUID.randomUUID().toString().replace("-",""),id,payment.get("amount"));
        }
    }
    public void closeNeverStarted(String orderId,String trace) {
        transactions.run(()->{var order=trade.lock(orderId);trade.lockResources(orderId);
            if(sql.count("SELECT COUNT(*) FROM payment WHERE order_id=?",orderId)==0) trade.closed(order,trace);return null;});
    }
    public void rejected(String key,String digest,boolean verified,String paymentNo,String trace) {
        transactions.run(()->{var payment=sql.one("SELECT * FROM payment WHERE payment_no=? AND provider='ALIPAY_SANDBOX'",paymentNo);
            sql.update("INSERT IGNORE INTO payment_event(payment_id,provider,event_key,event_type,verified,payload_digest,processing_status,processed_at,trace_id) VALUES(?,'ALIPAY_SANDBOX',?,'NOTIFY_REJECTED',?,?,'REJECTED',UTC_TIMESTAMP(3),?)",payment.map(r->r.get("id")).orElse(null),key,verified,digest,trace);
            if(verified) repository.issue("notify:"+digest,"NOTIFY_MISMATCH",payment.map(r->text(r,"order_id")).orElse(null),payment.map(r->text(r,"id")).orElse(null),null,null);return null;});
    }
}
