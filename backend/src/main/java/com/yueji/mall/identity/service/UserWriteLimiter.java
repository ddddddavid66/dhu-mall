package com.yueji.mall.identity.service;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UserWriteLimiter {
    private final LoginLimiter windows;
    public UserWriteLimiter(@Value("${yueji.security.write-limit:30}") int limit,Clock clock) {
        windows=new LoginLimiter(limit,clock);
    }
    public void check(String userId) { windows.check(userId); }
}
