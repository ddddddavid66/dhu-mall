package com.yueji.mall.settlement.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.settlement.model.SettlementModels.*;
import com.yueji.mall.settlement.service.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1")
public class SettlementController {
    private final SettlementService service;public SettlementController(SettlementService service){this.service=service;}
    @GetMapping("/finance/settlements") public Result<PageData<SettlementView>> financeList(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String shopId,@RequestParam(required=false) String status,@RequestParam(required=false) LocalDate periodDate,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.financeList(actor,shopId,status,periodDate,page),TraceIdFilter.current(request));}
    @PostMapping("/finance/settlements") @ResponseStatus(HttpStatus.CREATED) public Result<SettlementView> create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody SettlementInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.create(actor,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PostMapping("/finance/settlements/{id}/decision") public Result<SettlementView> decide(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody DecisionInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.decide(actor,id,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PostMapping("/finance/settlements/{id}/mock-payout") public Result<SettlementView> payout(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody PayoutInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.payout(actor,id,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/finance/settlements/{id}") public Result<SettlementView> financeView(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){return Result.ok(service.financeView(actor,id),TraceIdFilter.current(request));}
    @GetMapping("/merchant/shops/{shopId}/settlements") public Result<PageData<SettlementView>> merchantList(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@RequestParam(required=false) String status,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.merchantList(actor,shopId,status,page),TraceIdFilter.current(request));}
    @GetMapping("/merchant/shops/{shopId}/settlements/{id}") public Result<SettlementView> merchantView(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,HttpServletRequest request){return Result.ok(service.merchantView(actor,shopId,id),TraceIdFilter.current(request));}
    @PostMapping("/finance/settlement-adjustments") @ResponseStatus(HttpStatus.CREATED) public Result<AdjustmentView> createAdjustment(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AdjustmentInput input,HttpServletRequest request){return Result.ok(service.createAdjustment(actor,input,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/finance/settlement-adjustments") public Result<PageData<AdjustmentView>> adjustments(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String status,@RequestParam(required=false) String shopId,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.adjustments(actor,shopId,status,page),TraceIdFilter.current(request));}
    @PostMapping("/finance/settlement-adjustments/{id}/approve") public Result<AdjustmentView> approveAdjustment(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody AdjustmentDecisionInput input,HttpServletRequest request){return Result.ok(service.approveAdjustment(actor,id,input),TraceIdFilter.current(request));}
}
