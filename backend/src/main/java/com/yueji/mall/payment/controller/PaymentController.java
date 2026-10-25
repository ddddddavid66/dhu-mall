package com.yueji.mall.payment.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.service.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentService payments;private final AlipayNotificationService notifications;
    public PaymentController(PaymentService payments,AlipayNotificationService notifications) {this.payments=payments;this.notifications=notifications;}
    @PostMapping("/orders/{id}/payments")
    public ResponseEntity<Result<PaymentView>> create(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestBody(required=false) Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        if(body!=null && !body.isEmpty()) throw new BusinessException(ErrorCode.PARAM_INVALID);
        var result=payments.create(actor,id,key,TraceIdFilter.current(request));return ResponseEntity.status(result.created()?201:200).body(Result.ok(result.view(),TraceIdFilter.current(request)));
    }
    @GetMapping("/payments/{id}")
    public Result<PaymentView> view(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request) {return Result.ok(payments.view(actor,id,TraceIdFilter.current(request)),TraceIdFilter.current(request));}
    @PostMapping(value="/payments/alipay/notify",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    public String notify(HttpServletRequest request) {
        try {
            if(request.getQueryString()!=null) return "fail";
            Map<String,String> parameters=new HashMap<>();for(var e:request.getParameterMap().entrySet()) {if(e.getValue().length!=1) return "fail";parameters.put(e.getKey(),e.getValue()[0]);}
            return notifications.receive(parameters,TraceIdFilter.current(request))?"success":"fail";
        }catch(RuntimeException error) {return "fail";}
    }
}
