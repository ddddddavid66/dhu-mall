package com.yueji.mall.trade.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration @EnableScheduling
@ConditionalOnProperty(name="yueji.trade.expiry-enabled",havingValue="true",matchIfMissing=true)
public class OrderExpiryScheduler {
    private final OrderExpiryService expiry;private final String owner="expiry-"+UUID.randomUUID();
    public OrderExpiryScheduler(OrderExpiryService expiry) {this.expiry=expiry;}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void scan() {expiry.scan(owner);}
}
