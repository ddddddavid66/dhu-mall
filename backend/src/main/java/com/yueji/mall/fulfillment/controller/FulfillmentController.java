package com.yueji.mall.fulfillment.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.fulfillment.model.FulfillmentModels.*;
import com.yueji.mall.fulfillment.service.FulfillmentService;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.trade.model.TradeModels.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1")
public class FulfillmentController {
    private final FulfillmentService service;
    public FulfillmentController(FulfillmentService service){this.service=service;}
    @GetMapping("/merchant/shops/{shopId}/orders") public Result<PageData<com.yueji.mall.trade.model.TradeModels.OrderSummary>> orders(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@RequestParam(required=false) String status,@RequestParam(required=false) String orderNo,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.list(actor,shopId,status,orderNo,page),TraceIdFilter.current(request));}
    @GetMapping("/merchant/shops/{shopId}/orders/{id}") public Result<OrderView> order(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,HttpServletRequest request){return Result.ok(service.detail(actor,shopId,id),TraceIdFilter.current(request));}
    @PostMapping("/merchant/shops/{shopId}/orders/{id}/ship") @ResponseStatus(HttpStatus.CREATED) public Result<ShipmentView> ship(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,@Valid @RequestBody ShipInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.ship(actor,shopId,id,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PostMapping("/merchant/shops/{shopId}/shipments/{id}/events") @ResponseStatus(HttpStatus.CREATED) public Result<ShipmentView> event(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,@Valid @RequestBody EventInput input,HttpServletRequest request){return Result.ok(service.addEvent(actor,shopId,id,input,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PostMapping("/orders/{id}/confirm-receipt") public Result<OrderView> receipt(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return Result.ok(service.confirmReceipt(actor,id,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
}
