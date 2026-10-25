package com.yueji.mall.cart.controller;

import com.yueji.mall.cart.model.AddressInput;
import com.yueji.mall.cart.model.AddressView;
import com.yueji.mall.cart.service.AddressService;
import com.yueji.mall.common.api.Result;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/api/v1/addresses")
public class AddressController {
    private final AddressService service;
    public AddressController(AddressService service) { this.service=service; }
    @GetMapping
    public Result<List<AddressView>> list(@AuthenticationPrincipal Actor actor,HttpServletRequest request) {
        return Result.ok(service.list(actor),TraceIdFilter.current(request));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Result<AddressView> create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AddressInput input,HttpServletRequest request) {
        return Result.ok(service.create(actor,input),TraceIdFilter.current(request));
    }
    @PutMapping("/{id}")
    public Result<AddressView> update(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody AddressInput input,HttpServletRequest request) {
        return Result.ok(service.update(actor,id,input),TraceIdFilter.current(request));
    }
    @DeleteMapping("/{id}")
    public Result<Map<String,Boolean>> delete(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request) {
        service.delete(actor,id);
        return Result.ok(Map.of("success",true),TraceIdFilter.current(request));
    }
}
