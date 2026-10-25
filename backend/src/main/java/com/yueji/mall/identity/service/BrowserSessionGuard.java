package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BrowserSessionGuard {
    private final Set<String> origins;
    public BrowserSessionGuard(@Value("${yueji.allowed-origins}") String allowed) {
        origins=Arrays.stream(allowed.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    }
    public void origin(String origin,String csrf) {
        if (origin==null || !origins.contains(origin) || csrf==null || !csrf.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.CSRF_INVALID);
        }
    }
    public void csrf(String raw,String expectedHash) {
        if (!Crypto.matches(raw,expectedHash)) throw new BusinessException(ErrorCode.CSRF_INVALID);
    }
}
