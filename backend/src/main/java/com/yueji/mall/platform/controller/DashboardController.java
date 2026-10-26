package com.yueji.mall.platform.controller;

import com.yueji.mall.common.api.Result;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.platform.service.DashboardService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }
    @GetMapping("/api/v1/dashboard")
    public Result<Map<String,Object>> view(@AuthenticationPrincipal Actor actor,
            @RequestParam(required = false) String shopId, HttpServletRequest request) {
        return Result.ok(service.view(actor, shopId), TraceIdFilter.current(request));
    }
}
