package com.yueji.mall.payment.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.payment.model.ReconciliationModels.*;
import com.yueji.mall.payment.service.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1/finance")
public class ReconciliationController {
    private final ReconciliationService service;public ReconciliationController(ReconciliationService service){this.service=service;}
    @GetMapping("/reconciliations") public Result<PageData<ReconciliationView>> runs(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) LocalDate billDate,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.runs(actor,billDate,page),TraceIdFilter.current(request));}
    @PostMapping("/reconciliations") @ResponseStatus(HttpStatus.ACCEPTED) public Result<ReconciliationView> start(@AuthenticationPrincipal Actor actor,@Valid @RequestBody StartInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.start(actor,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/reconciliation-issues") public Result<PageData<IssueView>> issues(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String status,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.issues(actor,status,page),TraceIdFilter.current(request));}
    @PostMapping("/reconciliation-issues/{id}/resolve") public Result<IssueView> resolve(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody IssueResolutionInput input,HttpServletRequest request){return Result.ok(service.resolve(actor,id,input),TraceIdFilter.current(request));}
}
