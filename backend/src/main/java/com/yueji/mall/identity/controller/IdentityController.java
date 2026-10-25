package com.yueji.mall.identity.controller;

import com.yueji.mall.common.api.Result;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.model.IdentityInputs.*;
import com.yueji.mall.identity.model.IdentityViews.*;
import com.yueji.mall.identity.service.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden // OpenAPI 以静态契约为准，避免扫描另建一套 operationId。
@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final AccountService accounts;
    private final SessionService sessions;
    private final LoginLimiter limiter;
    private final Clock clock;
    private final boolean cookieSecure;
    public IdentityController(AccountService accounts,SessionService sessions,LoginLimiter limiter,Clock clock,
            @Value("${yueji.security.cookie-secure}") boolean cookieSecure,org.springframework.core.env.Environment environment) {
        this.accounts=accounts; this.sessions=sessions; this.limiter=limiter; this.clock=clock;
        this.cookieSecure=cookieSecure || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
    }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<UserView> register(@Valid @RequestBody RegisterInput input,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        return Result.ok(accounts.register(input),TraceIdFilter.current(request));
    }
    @PostMapping("/auth/login")
    public Result<AuthView> login(@Valid @RequestBody LoginInput input,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr());
        return issued(sessions.login(input),request,response);
    }
    @PostMapping("/auth/refresh")
    public Result<AuthView> refresh(@CookieValue(name="refreshToken",required=false) String refresh,
            @RequestHeader(name="X-CSRF-Token",required=false) String csrf,@RequestHeader(name="Origin",required=false) String origin,
            HttpServletRequest request,HttpServletResponse response) {
        return issued(sessions.refresh(refresh,csrf,origin),request,response);
    }
    @PostMapping("/auth/logout")
    public Result<Map<String,Boolean>> logout(@CookieValue(name="refreshToken",required=false) String refresh,
            @RequestHeader(name="X-CSRF-Token",required=false) String csrf,@RequestHeader(name="Origin",required=false) String origin,
            HttpServletRequest request,HttpServletResponse response) {
        sessions.logout(refresh,csrf,origin);
        cookie(response,"",Duration.ZERO);
        return Result.ok(Map.of("success",true),TraceIdFilter.current(request));
    }
    @GetMapping("/me")
    public Result<UserView> me(@AuthenticationPrincipal Actor actor,HttpServletRequest request) {
        return Result.ok(accounts.me(actor),TraceIdFilter.current(request));
    }
    @PutMapping("/me/password")
    public Result<Map<String,Boolean>> password(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PasswordInput input,HttpServletRequest request) {
        accounts.changePassword(actor,input);
        return Result.ok(Map.of("success",true),TraceIdFilter.current(request));
    }
    private Result<AuthView> issued(SessionService.Issued issued,HttpServletRequest request,HttpServletResponse response) {
        cookie(response,issued.refreshToken(),Duration.between(clock.instant(),issued.expiresAt()));
        return Result.ok(issued.body(),TraceIdFilter.current(request));
    }
    private void cookie(HttpServletResponse response,String value,Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("refreshToken",value).httpOnly(true).secure(cookieSecure)
                .sameSite("Lax").path("/api/v1/auth").maxAge(maxAge).build().toString());
    }
}
