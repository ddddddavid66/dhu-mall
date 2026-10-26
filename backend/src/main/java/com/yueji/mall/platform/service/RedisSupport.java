package com.yueji.mall.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.service.Crypto;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
public class RedisSupport {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final String prefix;
    private final Semaphore fallback = new Semaphore(20);
    private final java.util.concurrent.atomic.AtomicLong invalidationRequested = new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.concurrent.atomic.AtomicLong invalidationCompleted = new java.util.concurrent.atomic.AtomicLong();
    public RedisSupport(StringRedisTemplate redis,ObjectMapper mapper,MeterRegistry metrics,
            @Value("${yueji.redis.enabled:false}") boolean enabled,@Value("${yueji.redis.namespace:yueji.dev}") String prefix) {
        this.redis=redis;this.mapper=mapper;this.metrics=metrics;this.enabled=enabled;this.prefix=prefix+":";
        if(!prefix.matches("[A-Za-z0-9._-]{3,60}")) throw new IllegalArgumentException("Redis命名空间不合法");
    }
    public boolean enabled() {return enabled;}
    public void limit(String kind,String identifier,int maximum) {
        if(!enabled) return;
        try {
            Long count=redis.execute(LIMIT,List.of(prefix+"limit:"+kind+":"+Crypto.hash(identifier)));
            if(count==null) throw new IllegalStateException("Redis限流无结果");
            if(count>maximum) throw new BusinessException(ErrorCode.RATE_LIMITED);
        } catch(BusinessException rejected) {throw rejected;}
        catch(RuntimeException unavailable) {metrics.counter("yueji.redis.failures","operation","limit").increment();throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
    }
    public void requireAvailable() {
        if(!enabled) return;
        try {if(!"PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>)connection->connection.ping()))) throw new IllegalStateException("Redis未就绪");}
        catch(RuntimeException unavailable) {throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
    }
    public <T> T cached(String resource,Duration ttl,TypeReference<T> type,Supplier<T> load) {
        if(!enabled) return load.get();
        String key=null;
        try {
            // 启动与失效失败后先递增版本；旧键只等TTL淘汰，不扫描或清空Redis。
            flushInvalidation();
            String version=redis.opsForValue().get(prefix+"catalog.version");
            if(version==null) throw new IllegalStateException("资源版本缺失");
            key=prefix+"catalog:"+version+":"+resource;
            String value=redis.opsForValue().get(key);
            if(value!=null) {metrics.counter("yueji.cache.read","result","hit").increment();return mapper.readValue(value,type);}
        } catch(Exception unavailable) {metrics.counter("yueji.cache.read","result","fallback").increment();key=null;}
        if(!fallback.tryAcquire()) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        T result;
        try {result=load.get();} finally {fallback.release();}
        if(key!=null) {
            try {redis.opsForValue().set(key,mapper.writeValueAsString(result),ttl);}
            catch(Exception unavailable) {metrics.counter("yueji.redis.failures","operation","cache").increment();}
        }
        return result;
    }
    public void invalidateAfterCommit() {
        if(!enabled) return;
        Runnable invalidate=()-> {
            invalidationRequested.incrementAndGet();
            try {flushInvalidation();}
            catch(RuntimeException unavailable) {metrics.counter("yueji.redis.failures","operation","invalidate").increment();}
        };
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {invalidate.run();return;}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {invalidate.run();}
        });
    }
    private void flushInvalidation() {
        long generation=invalidationRequested.get();
        if(generation<=invalidationCompleted.get()) return;
        redis.opsForValue().increment(prefix+"catalog.version");
        // 只确认本次发送前的代次；并发发生的新失效不能被旧请求清除。
        invalidationCompleted.accumulateAndGet(generation,Math::max);
    }
}
