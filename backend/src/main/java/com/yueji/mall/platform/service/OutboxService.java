package com.yueji.mall.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.persistence.SqlStore;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 持久化事件，不将写库冒充 Broker 发布确认。未接发布者时始终保留 PENDING。 */
@Service
public class OutboxService {
    private final SqlStore sql;private final ObjectMapper mapper;private final Clock clock;
    public OutboxService(SqlStore sql,ObjectMapper mapper,Clock clock) {this.sql=sql;this.mapper=mapper;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void orderCreated(String order,String shop,String trace) {
        fact("OrderCreated",order,Map.of("orderId",order,"shopId",shop),trace);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void fact(String type,String order,Map<String,Object> data,String trace) {
        factFor("order",type,order,data,trace);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void factFor(String aggregateType,String type,String aggregate,Map<String,Object> data,String trace) {
        String event=UUID.randomUUID().toString();
        var envelope=Map.of("eventId",event,"eventType",type,"schemaVersion",1,"occurredAt",clock.instant().toString(),"aggregateId",aggregate,"traceId",trace,"data",data);
        try {
            sql.insert("INSERT INTO outbox_event(event_id,event_type,aggregate_type,aggregate_id,payload,trace_id,next_attempt_at) VALUES(?,?,?,?,?,?,?)",event,type,aggregateType,aggregate,mapper.writeValueAsString(envelope),trace,Timestamp.from(clock.instant()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("Outbox 序列化失败",error);}
    }
}
