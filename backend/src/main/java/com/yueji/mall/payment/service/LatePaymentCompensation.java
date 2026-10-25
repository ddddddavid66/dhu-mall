package com.yueji.mall.payment.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.trade.service.TradePaymentAccess;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class LatePaymentCompensation {
    private final SqlStore sql;private final DatabaseTransaction transactions;private final PaymentRepository repository;private final PaymentService payments;private final TradePaymentAccess trade;
    public LatePaymentCompensation(SqlStore sql,DatabaseTransaction transactions,PaymentRepository repository,PaymentService payments,TradePaymentAccess trade) {this.sql=sql;this.transactions=transactions;this.repository=repository;this.payments=payments;this.trade=trade;}
    public void process(String id,String trace) {
        var original=sql.required("SELECT * FROM refund WHERE id=? AND reason_type='LATE_PAYMENT_COMPENSATION'",id);
        var ticket=repository.ticket(repository.row(text(original,"payment_id")));
        var claimed=transactions.run(()->{
            trade.lock(ticket.orderId());trade.lockResources(ticket.orderId());sql.required("SELECT id FROM payment WHERE id=? FOR UPDATE",ticket.id());
            var refund=sql.required("SELECT * FROM refund WHERE id=? FOR UPDATE",id);
            if("SUCCEEDED".equals(refund.get("status"))) return null;
            // updated_at 是本次处理的持久化时间；进程崩溃后 30 秒可查原号，后续未知保持最长 5 分钟退避。
            if("PROCESSING".equals(refund.get("status")) && sql.count("SELECT COUNT(*) FROM refund WHERE id=? AND updated_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 SECOND)",id)>0) return null;
            var amount=(BigDecimal)refund.get("amount");
            var total=(BigDecimal)sql.required("SELECT COALESCE(SUM(amount),0) total FROM refund WHERE payment_id=? AND id<>? AND status IN ('PROCESSING','SUCCEEDED')",ticket.id(),id).get("total");
            if(total.add(amount).compareTo(ticket.amount())>0) throw new IllegalStateException("补偿退款超过实付金额");
            sql.update("UPDATE refund SET status='PROCESSING',updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);return refund;
        });
        if(claimed==null) return;
        String no=text(claimed,"refund_no");var amount=(BigDecimal)claimed.get("amount");
        try {
            var adapter=payments.adapter(ticket);RefundFact fact;
            if("CREATED".equals(claimed.get("status")) || "FAILED".equals(claimed.get("status"))) fact=adapter.refund(ticket,no,amount);
            else {fact=adapter.queryRefund(ticket,no,amount);if(fact.status()==RefundStatus.NOT_FOUND) fact=adapter.refund(ticket,no,amount);}
            if(fact.status()!=RefundStatus.SUCCEEDED) {unknown(id);return;}
            if(!no.equals(fact.refundNo()) || !ticket.paymentNo().equals(fact.paymentNo()) || fact.amount()==null || amount.compareTo(fact.amount())!=0) {unknown(id);return;}
            complete(ticket,id,fact,trace);
        }catch(BusinessException error) {unknown(id);}
    }
    /** Reconciles an independently verified channel refund fact without issuing another refund request. */
    public void reconcileSuccess(String id,RefundFact fact,String trace) {
        var original=sql.required("SELECT * FROM refund WHERE id=? AND reason_type='LATE_PAYMENT_COMPENSATION'",id);
        var ticket=repository.ticket(repository.row(text(original,"payment_id")));
        if(fact.status()!=RefundStatus.SUCCEEDED||!text(original,"refund_no").equals(fact.refundNo())||!ticket.paymentNo().equals(fact.paymentNo())
                ||fact.amount()==null||((BigDecimal)original.get("amount")).compareTo(fact.amount())!=0) {
            throw new BusinessException(com.yueji.mall.common.api.ErrorCode.STATE_CONFLICT);
        }
        complete(ticket,id,fact,trace);
    }
    private void unknown(String id) {sql.update("UPDATE refund SET failure_code='CHANNEL_UNKNOWN',updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PROCESSING'",id);}
    private void complete(Ticket ticket,String id,RefundFact fact,String trace) {
        transactions.run(()->{
            trade.lock(ticket.orderId());trade.lockResources(ticket.orderId());sql.required("SELECT id FROM payment WHERE id=? FOR UPDATE",ticket.id());
            var row=sql.required("SELECT * FROM refund WHERE id=? FOR UPDATE",id);if("SUCCEEDED".equals(row.get("status"))) return null;
            sql.update("UPDATE refund SET status='SUCCEEDED',channel_refund_no=?,succeeded_at=UTC_TIMESTAMP(3),failure_code=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",fact.channelNo(),id);
            trade.compensationRefunded(ticket.orderId(),id,fact.amount(),trace);
            // 差异保留可追溯的自动补偿说明；不将其他差异一并标为已解决。
            sql.update("UPDATE reconciliation_issue SET status='RESOLVED',resolution='原路全额补偿退款已核实',refund_id=?,resolved_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE issue_key=? AND status='OPEN'",id,"late:"+ticket.id());return null;
        });
    }
}
