package com.yueji.mall.payment.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="yueji.payment.recovery-enabled",havingValue="true",matchIfMissing=true)
public class PaymentRecoveryScheduler {
    private final PaymentRecovery recovery;private final String owner="payment-"+UUID.randomUUID();
    public PaymentRecoveryScheduler(PaymentRecovery recovery) {this.recovery=recovery;}
    @Scheduled(initialDelay=30000,fixedDelay=30000) public void scan() {recovery.scan(owner);}
}
