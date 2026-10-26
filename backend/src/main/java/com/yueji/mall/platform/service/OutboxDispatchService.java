package com.yueji.mall.platform.service;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.platform.messaging.ConfirmedEventSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OutboxDispatchService {
    private final SqlStore sql;
    private final DatabaseTransaction transactions;
    private final ConfirmedEventSender sender;
    private final MeterRegistry metrics;
    public OutboxDispatchService(SqlStore sql, DatabaseTransaction transactions, ConfirmedEventSender sender, MeterRegistry metrics) {
        this.sql=sql; this.transactions=transactions; this.sender=sender; this.metrics=metrics;
    }
    public int dispatch(String owner) {
        int published=0;
        for (int i=0; i<100; i++) {
            var event=transactions.run(() -> claim(owner));
            if (event==null) break;
            try {
                // 网络等待不持有数据库事务、行锁或连接。
                sender.send(event.get("payload").toString(),event.get("event_id").toString(),"facts",0);
                published += sql.update("UPDATE outbox_event SET status='PUBLISHED',published_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND lease_owner=? AND status='PENDING'",event.get("id"),owner);
                metrics.counter("yueji.outbox.publish", "result", "confirmed").increment();
            } catch (RuntimeException failure) {
                failed(event,owner);
                metrics.counter("yueji.outbox.publish", "result", "unknown").increment();
                // Broker故障时本轮停止，避免一次扫描造成100次超时。
                break;
            }
        }
        return published;
    }
    private Map<String,Object> claim(String owner) {
        var due=sql.one("SELECT * FROM outbox_event WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3)) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(due.isEmpty()) return null;
        var event=due.get();
        sql.update("UPDATE outbox_event SET lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),updated_at=UTC_TIMESTAMP(3) WHERE id=?",owner,event.get("id"));
        return event;
    }
    private void failed(Map<String,Object> event,String owner) {
        int attempts=((Number)event.get("attempts")).intValue()+1;
        int seconds=attempts==1?10:attempts==2?60:300;
        sql.update("UPDATE outbox_event SET attempts=?,status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND lease_owner=? AND status='PENDING'",attempts,attempts>10?"FAILED":"PENDING",seconds,event.get("id"),owner);
    }
}
