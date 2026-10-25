package com.yueji.mall.payment.adapter;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** 本地渠道事实使用 payment_event 持久化，业务处理另起事务，重启后可主动查询恢复。 */
@Component
public class LocalMockAdapter implements PaymentAdapter {
    private final SqlStore sql;private final DatabaseTransaction transactions;private final PaymentSettings settings;private final Clock clock;
    public LocalMockAdapter(SqlStore sql,DatabaseTransaction transactions,PaymentSettings settings,Clock clock) {this.sql=sql;this.transactions=transactions;this.settings=settings;this.clock=clock;}
    private void enabled() {if(!settings.mockEnabled) throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);}
    @Override public PaymentAction action(Ticket ticket) {enabled();return new PaymentAction("MOCK",null,ticket.expiresAt());}
    @Override public Fact query(Ticket ticket) {
        enabled();var paid=sql.one("SELECT created_at FROM payment_event WHERE provider='LOCAL_MOCK' AND event_key=?",key(ticket,"paid"));
        if(paid.isPresent()) return new Fact(FactStatus.PAID,ticket.paymentNo(),"MOCK-"+ticket.paymentNo(),ticket.amount(),RowValues.instant(paid.get().get("created_at")));
        boolean closed=sql.count("SELECT COUNT(*) FROM payment_event WHERE provider='LOCAL_MOCK' AND event_key=?",key(ticket,"closed"))>0;
        return new Fact(closed?FactStatus.CLOSED:FactStatus.WAITING,ticket.paymentNo(),null,ticket.amount(),null);
    }
    public Fact confirm(Ticket ticket) {
        enabled();return transactions.run(()->{lock(ticket);var before=query(ticket);
            if(before.status()==FactStatus.CLOSED) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            record(ticket,"paid");return query(ticket);
        });
    }
    @Override public Fact close(Ticket ticket) {
        enabled();return transactions.run(()->{lock(ticket);var fact=query(ticket);if(fact.status()!=FactStatus.PAID) record(ticket,"closed");return query(ticket);});
    }
    private void lock(Ticket ticket) {sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",ticket.orderId());sql.required("SELECT id FROM payment WHERE id=? FOR UPDATE",ticket.id());}
    private String key(Ticket ticket,String kind) {return "mock:"+ticket.paymentNo()+":"+kind;}
    private void record(Ticket ticket,String kind) {
        sql.update("INSERT IGNORE INTO payment_event(payment_id,provider,event_key,event_type,verified,payload_digest,trace_id) VALUES(?,'LOCAL_MOCK',?,'MOCK_CHANNEL',1,?,'local-mock-channel')",ticket.id(),key(ticket,kind),Crypto.hash(key(ticket,kind)));
    }
    @Override public RefundFact refund(Ticket ticket,String no,BigDecimal amount) {
        enabled();return transactions.run(()->{lock(ticket);if(query(ticket).status()!=FactStatus.PAID || amount.signum()<=0 || amount.compareTo(ticket.amount())>0) return new RefundFact(RefundStatus.UNKNOWN,no,ticket.paymentNo(),null,null);
            record(ticket,"refund:"+no);return queryRefund(ticket,no,amount);});
    }
    @Override public RefundFact queryRefund(Ticket ticket,String no,BigDecimal amount) {
        enabled();boolean found=sql.count("SELECT COUNT(*) FROM payment_event WHERE provider='LOCAL_MOCK' AND event_key=?",key(ticket,"refund:"+no))>0;
        return new RefundFact(found?RefundStatus.SUCCEEDED:RefundStatus.NOT_FOUND,no,ticket.paymentNo(),amount,"MOCK-"+no);
    }
}
