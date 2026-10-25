package com.yueji.mall.platform.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.platform.repository.AuditQueries;
import com.yueji.mall.platform.service.AuditService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditController {
    private final AuditService service;
    public AuditController(AuditService service) { this.service=service; }
    @GetMapping
    public Result<PageData<AuditQueries.AuditView>> list(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,
            @RequestParam(required=false) String actorId,@RequestParam(required=false) String action,
            @RequestParam(required=false) String traceId,@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,HttpServletRequest request) {
        return Result.ok(service.list(actor,page,actorId,action,traceId,from,to),TraceIdFilter.current(request));
    }
}
