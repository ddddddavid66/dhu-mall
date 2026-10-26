package com.yueji.mall.platform.service;

import com.yueji.mall.common.persistence.SqlStore;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

/** 只使用有限指标名，不将订单、用户或traceId作为标签；查询失败返回NaN。 */
@Component
public class OperationalMetrics {
    public OperationalMetrics(SqlStore sql,MeterRegistry registry) {
        register(sql,registry,"yueji.outbox.oldest.seconds","SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(3)),0) FROM outbox_event WHERE status='PENDING'");
        register(sql,registry,"yueji.outbox.failed.count","SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'");
        register(sql,registry,"yueji.payment.closing.oldest.seconds","SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(updated_at),UTC_TIMESTAMP(3)),0) FROM trade_order WHERE status='CLOSING'");
        register(sql,registry,"yueji.refund.processing.oldest.seconds","SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(updated_at),UTC_TIMESTAMP(3)),0) FROM refund WHERE status='PROCESSING'");
        register(sql,registry,"yueji.inventory.constraint.violations","SELECT COUNT(*) FROM inventory WHERE on_hand<reserved OR reserved<0");
    }
    private void register(SqlStore sql,MeterRegistry registry,String name,String statement) {
        Gauge.builder(name,sql,s->{try{return (double)s.count(statement);}catch(RuntimeException unavailable){return Double.NaN;}}).register(registry);
    }
}
