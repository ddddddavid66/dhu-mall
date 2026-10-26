package com.yueji.mall.platform.service;

import com.fasterxml.jackson.databind.*;
import com.yueji.mall.common.persistence.SqlStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 异步核验并记录事实观察；业务金额和库存已在原事务完成，消费者不会再次修改。 */
@Service
public class EventFactConsumer {
    public static final String NAME="fact-audit-v1";
    private final SqlStore sql;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public EventFactConsumer(SqlStore sql,ObjectMapper mapper,MeterRegistry metrics) {this.sql=sql;this.mapper=mapper;this.metrics=metrics;}
    @Transactional
    public boolean consume(String body) {
        try {
            JsonNode envelope=mapper.readTree(body);
            String eventId=envelope.path("eventId").asText();
            var stored=sql.required("SELECT * FROM outbox_event WHERE event_id=?",eventId);
            if(!envelope.equals(mapper.readTree(stored.get("payload").toString()))) throw new IllegalArgumentException("事件必须与持久事实完全一致");
            String kind=stored.get("aggregate_type").toString();
            String table=switch(kind) {case "order"->"trade_order"; case "settlement"->"settlement"; default->throw new IllegalArgumentException("未知聚合");};
            // 复核当前事实，不要求旧事件状态与当前状态相同，因此允许乱序。
            sql.required("SELECT id FROM "+table+" WHERE id=?",stored.get("aggregate_id"));
            int inserted=sql.update("INSERT IGNORE INTO consumed_event(consumer_name,event_id,processed_at) VALUES(?,?,UTC_TIMESTAMP(3))",NAME,eventId);
            if(inserted==0) {metrics.counter("yueji.event.consume","result","duplicate").increment();return false;}
            sql.insert("INSERT INTO audit_log(action,resource_type,resource_id,result,summary,trace_id) VALUES('event.fact_observed',?,?,'SUCCESS',JSON_OBJECT('eventId',?,'eventType',?),?)",kind,stored.get("aggregate_id"),eventId,stored.get("event_type"),stored.get("trace_id"));
            metrics.counter("yueji.event.consume","result","processed").increment();
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalArgumentException("无效事件JSON",error);}
    }
}
