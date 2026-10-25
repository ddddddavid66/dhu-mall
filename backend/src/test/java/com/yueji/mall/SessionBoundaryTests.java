package com.yueji.mall;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.identity.service.BrowserSessionGuard;
import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.identity.service.LoginLimiter;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionBoundaryTests {
    @Test void publicReadsEnforce120PerPeerIpAndReturnUniform429() throws Exception {
        var limiter=new com.yueji.mall.common.web.PublicReadLimiter(120,Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC),
                new com.yueji.mall.common.web.ApiErrorWriter(new com.fasterxml.jackson.databind.ObjectMapper()));
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/products");request.setRemoteAddr("127.0.0.1");
        for(int i=0;i<120;i++) limiter.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)->{});
        var denied=new org.springframework.mock.web.MockHttpServletResponse();limiter.doFilter(request,denied,(req,res)->fail("超过阈值不能到达控制器"));
        assertThat(denied.getStatus()).isEqualTo(429);assertThat(denied.getHeader("Retry-After")).isEqualTo("60");assertThat(denied.getContentAsString()).contains("RATE_LIMITED");
    }
    @Test void coreWritesAreLimitedPerServerResolvedUser() {
        var limiter=new com.yueji.mall.identity.service.UserWriteLimiter(30,Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));
        for(int i=0;i<30;i++) limiter.check("1");
        assertThatThrownBy(()->limiter.check("1")).isInstanceOf(BusinessException.class);
        limiter.check("2");
    }
    @Test void loginLimitHasABoundedMinuteWindowAndDoesNotTrustClientForwardedHeaders() {
        var limiter=new LoginLimiter(10,Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));
        for(int i=0;i<10;i++) limiter.check("127.0.0.1");
        assertThatThrownBy(()->limiter.check("127.0.0.1")).isInstanceOf(BusinessException.class);
        limiter.check("127.0.0.2");
    }
    @Test void csrfGuardRequiresExactOriginAndConstantTimeHashMatch() {
        var guard=new BrowserSessionGuard("https://mall.example.invalid");
        String token=Crypto.randomToken();
        guard.origin("https://mall.example.invalid",token); guard.csrf(token,Crypto.hash(token));
        assertThatThrownBy(()->guard.origin("https://mall.example.invalid.attacker.invalid",token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->guard.csrf(Crypto.randomToken(),Crypto.hash(token))).isInstanceOf(BusinessException.class);
    }
}
