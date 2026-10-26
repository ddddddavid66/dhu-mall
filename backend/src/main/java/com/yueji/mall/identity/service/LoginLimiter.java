package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginLimiter {
    private record Window(long minute,int count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;
    private com.yueji.mall.platform.service.RedisSupport redis;
    public LoginLimiter(int limit,Clock clock) { this.limit=limit; this.clock=clock; }
    @org.springframework.beans.factory.annotation.Autowired
    public LoginLimiter(@Value("${yueji.security.login-limit}") int limit,Clock clock,com.yueji.mall.platform.service.RedisSupport redis) {
        this(limit,clock);this.redis=redis;
    }
    public void check(String ip) {
        if(redis!=null && redis.enabled()) {redis.limit("login",ip,limit);return;}
        long minute=clock.instant().getEpochSecond()/60;
        if (windows.size()>10000) windows.entrySet().removeIf(e -> e.getValue().minute()<minute);
        if (windows.size()>10000) throw new BusinessException(ErrorCode.RATE_LIMITED);
        var value=windows.compute(ip,(key,old) -> new Window(minute,old==null || old.minute()!=minute ? 1 : old.count()+1));
        if (value.count()>limit) throw new BusinessException(ErrorCode.RATE_LIMITED);
    }
}
