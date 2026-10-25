package com.yueji.mall.payment.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @Profile("!prod & (dev | test)")
@ConditionalOnExpression("'${yueji.payment.provider:LOCAL_MOCK}' == 'LOCAL_MOCK' && ${yueji.mock-payment-enabled:false}")
public class MockPaymentController {
    private final PaymentService payments;
    public MockPaymentController(PaymentService payments) {this.payments=payments;}
    @PostMapping("/api/v1/payments/{id}/mock-confirm")
    public Result<PaymentView> confirm(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestBody(required=false) java.util.Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        if(body!=null && !body.isEmpty()) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return Result.ok(payments.confirm(actor,id,key,TraceIdFilter.current(request)),TraceIdFilter.current(request));
    }
}
