package com.yueji.mall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.yueji.mall.common.api.*;
import com.yueji.mall.platform.service.RedisSupport;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "yueji.redis.enabled=true","yueji.redis.namespace=yueji.integration",
        "yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class RedisIntegrationTests extends StageEFixtures {
    static final TcpFaultProxy proxy=new TcpFaultProxy(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","16379")));
    @org.springframework.test.context.DynamicPropertySource
    static void proxyPort(org.springframework.test.context.DynamicPropertyRegistry registry) {registry.add("spring.data.redis.port",proxy::port);}
    @Autowired RedisSupport cache;
    @Autowired org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory connection;
    @Test void cachedDisplayHasVersionInvalidationButRollbackKeepsExistingVersion() {
        var calls=new AtomicInteger();String resource="test-"+UUID.randomUUID();
        var type=new TypeReference<Map<String,Integer>>() {};
        var first=cache.cached(resource,Duration.ofSeconds(5),type,()->Map.of("value",calls.incrementAndGet()));
        assertThat(cache.cached(resource,Duration.ofSeconds(5),type,()->Map.of("value",calls.incrementAndGet()))).isEqualTo(first);
        assertThatThrownBy(()->transactions.run(()->{cache.invalidateAfterCommit();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(cache.cached(resource,Duration.ofSeconds(5),type,()->Map.of("value",calls.incrementAndGet()))).isEqualTo(first);
        transactions.run(()->{cache.invalidateAfterCommit();return true;});
        assertThat(cache.cached(resource,Duration.ofSeconds(5),type,()->Map.of("value",calls.incrementAndGet())).get("value")).isEqualTo(2);
    }
    @Test void atomicRateLimitRejectsAboveBudgetAndDoesNotUseRawIdentifiers() {
        String id=UUID.randomUUID().toString();cache.limit("test",id,2);cache.limit("test",id,2);
        assertThatThrownBy(()->cache.limit("test",id,2)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
        cache.requireAvailable();
    }
    @Test void realRedisConnectionLossFallsBackForReadsRejectsWritesAndRecovers() {
        proxy.available(false);connection.resetConnection();
        try {
            cache.invalidateAfterCommit();
            var value=cache.cached("outage-"+UUID.randomUUID(),Duration.ofSeconds(5),new TypeReference<Map<String,String>>() {},()->Map.of("source","database"));
            assertThat(value).containsEntry("source","database");
            assertThatThrownBy(()->cache.limit("write",a.id(),30)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
            assertThatThrownBy(cache::requireAvailable).isInstanceOf(BusinessException.class);
            error(call("PUT","/cart/items/1",Map.of("quantity",1),a,null),503,"DEPENDENCY_UNAVAILABLE");
            assertThat(sql.count("SELECT COUNT(*) FROM trade_order")).isZero();
        } finally {proxy.available(true);connection.resetConnection();}
        cache.requireAvailable();
        cache.limit("recovered",UUID.randomUUID().toString(),1);
    }
    @Test void disabledRedisDoesNotBecomeAnImplicitRuntimeDependency() {
        var template=org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var disabled=new RedisSupport(template,mapper,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),false,"yueji.disabled-test");
        disabled.limit("test","identifier",1);disabled.requireAvailable();disabled.invalidateAfterCommit();
        assertThat(disabled.cached("resource",Duration.ofSeconds(5),new TypeReference<String>() {},()->"database")).isEqualTo("database");
        org.mockito.Mockito.verifyNoInteractions(template);
    }
}
