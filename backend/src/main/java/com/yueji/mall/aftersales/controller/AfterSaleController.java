package com.yueji.mall.aftersales.controller;

import com.yueji.mall.aftersales.model.AfterSaleModels.*;
import com.yueji.mall.aftersales.service.AfterSaleService;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1")
public class AfterSaleController {
    private final AfterSaleService service;
    public AfterSaleController(AfterSaleService service){this.service=service;}
    @PostMapping("/after-sales") @ResponseStatus(HttpStatus.CREATED) public Result<AfterSaleView> apply(@AuthenticationPrincipal Actor actor,@Valid @RequestBody ApplyInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.apply(actor,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/after-sales") public Result<PageData<AfterSaleView>> mine(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String status,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.mine(actor,status,page),TraceIdFilter.current(request));}
    @GetMapping("/after-sales/{id}") public Result<AfterSaleView> mineView(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){return Result.ok(service.mineView(actor,id),TraceIdFilter.current(request));}
    @PostMapping("/after-sales/{id}/cancel") public Result<AfterSaleView> cancel(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){return Result.ok(service.cancel(actor,id,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PutMapping("/after-sales/{id}/return-shipment") public Result<AfterSaleView> returnShipment(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody ReturnInput input,HttpServletRequest request){return Result.ok(service.returnShipment(actor,id,input),TraceIdFilter.current(request));}
    @GetMapping("/support/after-sales") public Result<PageData<AfterSaleView>> supportList(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String status,@RequestParam(required=false) String shopId,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.supportList(actor,status,shopId,page),TraceIdFilter.current(request));}
    @GetMapping("/support/after-sales/{id}") public Result<AfterSaleView> supportView(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){return Result.ok(service.supportView(actor,id),TraceIdFilter.current(request));}
    @PostMapping("/support/after-sales/{id}/decision") public Result<AfterSaleView> decide(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody DecisionInput input,HttpServletRequest request){return Result.ok(service.decide(actor,id,input),TraceIdFilter.current(request));}
    @GetMapping("/merchant/shops/{shopId}/after-sales") public Result<PageData<AfterSaleView>> merchantList(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@RequestParam(required=false) String status,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.merchantList(actor,shopId,status,page),TraceIdFilter.current(request));}
    @PostMapping("/merchant/shops/{shopId}/after-sales/{id}/inspection") public Result<AfterSaleView> inspect(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,@Valid @RequestBody InspectionInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.inspect(actor,shopId,id,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/support/after-sales/{id}/order") public Result<OrderSnapshot> supportOrder(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){return Result.ok(service.supportOrder(actor,id),TraceIdFilter.current(request));}
}
