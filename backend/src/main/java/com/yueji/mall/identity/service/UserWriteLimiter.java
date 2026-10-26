package com.yueji.mall.identity.service;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UserWriteLimiter {
    private final LoginLimiter windows;
    private final com.yueji.mall.platform.service.RedisSupport redis;
    private final int limit;
    public UserWriteLimiter(int limit,Clock clock) {this(limit,clock,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public UserWriteLimiter(@Value("${yueji.security.write-limit:30}") int limit,Clock clock,com.yueji.mall.platform.service.RedisSupport redis) {
        this.redis=redis;this.limit=limit;
        windows=new LoginLimiter(limit,clock);
    }
    public void check(String userId) { if(redis!=null && redis.enabled()) redis.limit("write",userId,limit);else windows.check(userId); }
}
