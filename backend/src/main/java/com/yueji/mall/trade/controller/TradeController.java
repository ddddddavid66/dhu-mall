package com.yueji.mall.trade.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.trade.model.TradeModels.*;
import com.yueji.mall.trade.service.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @RequestMapping("/api/v1")
public class TradeController {
    private final CheckoutService checkout;private final OrderService orders;
    public TradeController(CheckoutService checkout,OrderService orders) {this.checkout=checkout;this.orders=orders;}
    @PostMapping("/checkout/previews") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Result<PreviewView> preview(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PreviewInput input,HttpServletRequest request) {return Result.ok(checkout.preview(actor,input),TraceIdFilter.current(request));}
    @PostMapping("/checkout/orders") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Result<BatchView> create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody CreateOrderInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {return Result.ok(checkout.create(actor,input,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @GetMapping("/checkout/batches/{id}")
    public Result<BatchView> batch(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request) {return Result.ok(orders.batch(actor,id),TraceIdFilter.current(request));}
    @GetMapping("/orders")
    public Result<PageData<OrderSummary>> list(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) OrderStatus status,@RequestParam(required=false) String orderNo,@ModelAttribute PageQuery page,HttpServletRequest request) {return Result.ok(orders.list(actor,status,orderNo,page),TraceIdFilter.current(request));}
    @GetMapping("/orders/{id}")
    public Result<OrderView> view(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request) {return Result.ok(orders.view(actor,id),TraceIdFilter.current(request));}
    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<Result<OrderView>> cancel(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody CancelInput input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        var view=orders.cancel(actor,id,input,key,TraceIdFilter.current(request));
        return ResponseEntity.status("CLOSING".equals(view.status())?202:200).body(Result.ok(view,TraceIdFilter.current(request)));
    }
}
