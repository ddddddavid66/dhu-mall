package com.yueji.mall.payment.controller;

import com.yueji.mall.aftersales.model.AfterSaleModels.RefundView;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.payment.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1/finance/refunds")
public class RefundController {
    private final RefundService service;public RefundController(RefundService service){this.service=service;}
    @GetMapping public Result<PageData<RefundView>> list(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String status,@RequestParam(required=false) String shopId,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.list(actor,status,shopId,page),TraceIdFilter.current(request));}
    @PostMapping("/{id}/execute") @ResponseStatus(HttpStatus.ACCEPTED) public Result<RefundView> execute(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.execute(actor,id,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
}
