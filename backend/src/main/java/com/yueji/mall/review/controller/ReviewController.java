package com.yueji.mall.review.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.review.model.ReviewModels.*;
import com.yueji.mall.review.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden @RestController @RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService service;public ReviewController(ReviewService service){this.service=service;}
    @PostMapping("/reviews") @ResponseStatus(HttpStatus.CREATED) public Result<ReviewView> create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody ReviewInput input,HttpServletRequest request){return Result.ok(service.create(actor,input,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PutMapping("/ops/reviews/{id}/visibility") public Result<ReviewView> visibility(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody VisibilityInput input,HttpServletRequest request){return Result.ok(service.visibility(actor,id,input),TraceIdFilter.current(request));}
    @GetMapping("/ops/reviews") public Result<PageData<ReviewView>> list(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String productId,@RequestParam(required=false) String status,@ModelAttribute PageQuery page,HttpServletRequest request){return Result.ok(service.list(actor,productId,status,page),TraceIdFilter.current(request));}
}
