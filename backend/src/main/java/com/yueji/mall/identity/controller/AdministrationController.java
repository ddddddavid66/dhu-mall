package com.yueji.mall.identity.controller;

import com.yueji.mall.common.api.PageData;
import com.yueji.mall.common.api.PageQuery;
import com.yueji.mall.common.api.Result;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.model.IdentityInputs.*;
import com.yueji.mall.identity.model.IdentityViews.*;
import com.yueji.mall.identity.service.AdministrationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/api/v1/admin")
public class AdministrationController {
    private final AdministrationService service;
    public AdministrationController(AdministrationService service) { this.service=service; }
    @GetMapping("/users")
    public Result<PageData<UserView>> users(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) Status status,HttpServletRequest request) {
        return ok(service.users(actor,page,keyword,status),request);
    }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public Result<UserView> createUser(@AuthenticationPrincipal Actor actor,@Valid @RequestBody UserCreate input,HttpServletRequest request) {
        return ok(service.createUser(actor,input),request);
    }
    @PutMapping("/users/{id}")
    public Result<UserView> editUser(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody UserEdit input,HttpServletRequest request) {
        return ok(service.editUser(actor,id,input),request);
    }
    @GetMapping("/roles")
    public Result<PageData<RoleView>> roles(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,HttpServletRequest request) {
        return ok(service.roles(actor,page),request);
    }
    @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED)
    public Result<RoleView> createRole(@AuthenticationPrincipal Actor actor,@Valid @RequestBody RoleCreate input,HttpServletRequest request) {
        return ok(service.createRole(actor,input),request);
    }
    @PutMapping("/roles/{id}")
    public Result<RoleView> editRole(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody RoleEdit input,HttpServletRequest request) {
        return ok(service.editRole(actor,id,input),request);
    }
    @GetMapping("/permissions")
    public Result<List<PermissionView>> permissions(@AuthenticationPrincipal Actor actor,HttpServletRequest request) { return ok(service.permissions(actor),request); }
    @GetMapping("/shops")
    public Result<PageData<ShopView>> shops(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,
            @RequestParam(required=false) ShopStatus status,HttpServletRequest request) { return ok(service.shops(actor,page,status),request); }
    @PostMapping("/shops") @ResponseStatus(HttpStatus.CREATED)
    public Result<ShopView> createShop(@AuthenticationPrincipal Actor actor,@Valid @RequestBody ShopInput input,HttpServletRequest request) { return ok(service.createShop(actor,input),request); }
    @PutMapping("/shops/{id}")
    public Result<ShopView> editShop(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody ShopInput input,HttpServletRequest request) { return ok(service.editShop(actor,id,input),request); }
    @GetMapping("/shops/{shopId}/members")
    public Result<PageData<MemberView>> members(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,
            @ModelAttribute PageQuery page,HttpServletRequest request) { return ok(service.members(actor,shopId,page),request); }
    @PutMapping("/shops/{shopId}/members/{userId}/{duty}")
    public ResponseEntity<Result<MemberView>> member(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,
            @PathVariable String userId,@PathVariable Duty duty,@Valid @RequestBody MemberInput input,HttpServletRequest request) {
        var result=service.setMember(actor,shopId,userId,duty,input);
        return ResponseEntity.status(result.created() ? 201 : 200).body(ok(result.member(),request));
    }
    private <T> Result<T> ok(T value,HttpServletRequest request) { return Result.ok(value,TraceIdFilter.current(request)); }
}
