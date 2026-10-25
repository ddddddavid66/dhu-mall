package com.yueji.mall.inventory.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.inventory.model.InventoryModels.*;
import com.yueji.mall.inventory.service.InventoryService;
import com.yueji.mall.identity.model.Actor;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @RequestMapping("/api/v1/merchant/shops/{shopId}/inventory")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) {this.service=service;}
    @GetMapping
    public Result<PageData<InventoryView>> list(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@ModelAttribute PageQuery page,
            @RequestParam(required=false) String skuId,HttpServletRequest request) {return Result.ok(service.list(actor,shopId,skuId,page),TraceIdFilter.current(request));}
    @PostMapping("/{skuId}/adjustments") @ResponseStatus(HttpStatus.CREATED)
    public Result<InventoryView> adjust(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String skuId,
            @Valid @RequestBody Adjustment input,@RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        String trace=TraceIdFilter.current(request);return Result.ok(service.adjust(actor,shopId,skuId,input,key,trace),trace);
    }
}
