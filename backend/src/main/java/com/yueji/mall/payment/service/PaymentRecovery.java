package com.yueji.mall.payment.service;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(PaymentRecovery.class);
    private final SqlStore sql;private final DatabaseTransaction transactions;private final PaymentService payments;private final PaymentFacts facts;private final PaymentRepository repository;private final LatePaymentCompensation refunds;
    public PaymentRecovery(SqlStore sql,DatabaseTransaction transactions,PaymentService payments,PaymentFacts facts,PaymentRepository repository,LatePaymentCompensation refunds,MeterRegistry meters) {
        this.sql=sql;this.transactions=transactions;this.payments=payments;this.facts=facts;this.repository=repository;this.refunds=refunds;
        meters.gauge("yueji.payment.closing.unresolved",this,self->self.unresolved());
        meters.gauge("yueji.payment.compensation.pending",this,self->self.sql.count("SELECT COUNT(*) FROM refund WHERE reason_type='LATE_PAYMENT_COMPENSATION' AND status<>'SUCCEEDED'"));
    }
    public long unresolved() {return sql.count("SELECT COUNT(*) FROM trade_order o WHERE o.status='CLOSING' AND EXISTS(SELECT 1 FROM order_status_log l WHERE l.order_id=o.id AND l.to_status='CLOSING' AND l.created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 15 MINUTE))");}
    public int scan(String owner) {
        if(!lease(owner)) return 0;
        String trace="recovery-"+UUID.randomUUID();int checked=0;
        // 每批最多五笔，外部调用各自有超时；租约 300 秒，大于该批支付/退款请求预算。
        for(var r:sql.rows("SELECT o.id FROM trade_order o LEFT JOIN payment p ON p.order_id=o.id WHERE o.status='CLOSING' AND p.id IS NULL ORDER BY o.id LIMIT 100")) facts.closeNeverStarted(r.get("id").toString(),trace);
        for(var r:sql.rows("SELECT * FROM payment WHERE status IN ('INIT','PENDING') ORDER BY COALESCE(last_checked_at,created_at),id LIMIT 5")) {payments.check(repository.ticket(r),trace,false);checked++;}
        for(var r:sql.rows("SELECT id FROM refund WHERE reason_type='LATE_PAYMENT_COMPENSATION' AND status IN ('CREATED','PROCESSING','FAILED') ORDER BY updated_at,id LIMIT 5")) refunds.process(r.get("id").toString(),trace);
        long stuck=unresolved();if(stuck>0) LOG.warn("payment_close_unresolved count={} thresholdMinutes=15 traceId={}",stuck,trace);
        return checked;
    }
    private boolean lease(String owner) {
        return transactions.run(()->{
            sql.update("INSERT IGNORE INTO job_lease(job_name,owner,lease_until) VALUES('payment-recovery',?,UTC_TIMESTAMP(3))",owner);
            sql.required("SELECT id FROM job_lease WHERE job_name='payment-recovery' FOR UPDATE");
            if(sql.count("SELECT COUNT(*) FROM job_lease WHERE job_name='payment-recovery' AND lease_until>UTC_TIMESTAMP(3) AND owner<>?",owner)>0) return false;
            sql.update("UPDATE job_lease SET owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 300 SECOND),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE job_name='payment-recovery'",owner);return true;
        });
    }
}
