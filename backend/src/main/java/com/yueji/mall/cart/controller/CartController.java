package com.yueji.mall.cart.controller;

import com.yueji.mall.common.api.Result;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.cart.model.CartModels.*;
import com.yueji.mall.cart.service.CartService;
import com.yueji.mall.identity.model.Actor;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @RequestMapping("/api/v1/cart")
public class CartController {
    private final CartService service;
    public CartController(CartService service) {this.service=service;}
    @GetMapping
    public Result<CartView> list(@AuthenticationPrincipal Actor actor,HttpServletRequest request) {return Result.ok(service.list(actor),TraceIdFilter.current(request));}
    @PutMapping("/items/{skuId}")
    public Result<CartView> set(@AuthenticationPrincipal Actor actor,@PathVariable String skuId,@Valid @RequestBody CartSet input,HttpServletRequest request) {return Result.ok(service.set(actor,skuId,input),TraceIdFilter.current(request));}
    @DeleteMapping("/items/{skuId}")
    public Result<Map<String,Boolean>> delete(@AuthenticationPrincipal Actor actor,@PathVariable String skuId,HttpServletRequest request) {service.delete(actor,skuId);return Result.ok(Map.of("success",true),TraceIdFilter.current(request));}
}
