package com.yueji.mall.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.service.Crypto;
import java.time.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class IdempotencyService {
    private final SqlStore sql;private final ObjectMapper mapper;private final Clock clock;
    public IdempotencyService(SqlStore sql,ObjectMapper mapper,Clock clock) {this.sql=sql;this.mapper=mapper;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public <T> T execute(Actor actor,String operation,String key,Object request,String resource,String resourceId,Class<T> type,Supplier<T> action) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{20,64}")) throw new BusinessException(ErrorCode.PARAM_INVALID);
        try {
            String hash=Crypto.hash(mapper.writeValueAsString(request));
            var old=sql.one("SELECT * FROM idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=? FOR UPDATE",actor.userId(),operation,key);
            if(old.isPresent()) {
                var row=old.get();
                if(!RowValues.instant(row.get("replay_until")).isAfter(clock.instant())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
                if(!hash.equals(row.get("request_hash"))) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                if(!"SUCCEEDED".equals(row.get("status"))) throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
                return mapper.readValue(text(row,"response_snapshot"),type);
            }
            // 调用方先持有用户锁；同用户同键串行。幂等行、库存、流水及响应快照一起提交或回滚。
            String id=sql.insert("INSERT INTO idempotency_record(user_id,operation_id,idempotency_key,request_hash,replay_until,retain_until) VALUES(?,?,?,?,?,?)",actor.userId(),operation,key,hash,java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(24))),java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(30))));
            T result=action.get();
            sql.update("UPDATE idempotency_record SET status='SUCCEEDED',resource_type=?,resource_id=?,response_snapshot=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",resource,resourceId,mapper.writeValueAsString(result),id);
            return result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("幂等快照序列化失败",error);}
    }
}
