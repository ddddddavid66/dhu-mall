package com.yueji.mall.aftersales.service;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AfterSaleExpiryScheduler {
    private final AfterSaleService service;
    public AfterSaleExpiryScheduler(AfterSaleService service){this.service=service;}
    @Scheduled(fixedDelay=60_000,initialDelay=60_000) public void expire(){service.expireReturns("after-sale-expiry-"+UUID.randomUUID());}
}
