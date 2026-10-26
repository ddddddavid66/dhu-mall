package com.yueji.mall.platform.messaging;

import com.yueji.mall.platform.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="yueji.messaging.enabled",havingValue="true")
public class EventWorker {
    private final OutboxDispatchService dispatch;
    private final EventFactConsumer consumer;
    private final ConfirmedEventSender sender;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final String queue;
    private final String owner="outbox-"+UUID.randomUUID();
    @Value("${yueji.messaging.scheduling-enabled:true}") private boolean schedulingEnabled;
    public EventWorker(OutboxDispatchService dispatch,EventFactConsumer consumer,ConfirmedEventSender sender,
            RabbitTemplate rabbit,MeterRegistry metrics,@Value("${yueji.messaging.namespace:yueji.dev}") String prefix) {
        this.dispatch=dispatch;this.consumer=consumer;this.sender=sender;this.rabbit=rabbit;this.metrics=metrics;this.queue=prefix+".facts";
    }
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    public void tick() {
        if(!schedulingEnabled) return;
        try {dispatch.dispatch(owner);for(int i=0;i<100 && consumeOne();i++) { /* 有界拉取，避免积压占满线程。 */ }}
        catch(RuntimeException unavailable) {metrics.counter("yueji.event.worker","result","unavailable").increment();}
    }
    public boolean consumeOne() {
        return Boolean.TRUE.equals(rabbit.execute(channel -> {
            var delivery=channel.basicGet(queue,false);
            if(delivery==null) return false;
            long tag=delivery.getEnvelope().getDeliveryTag();
            String body=new String(delivery.getBody(),StandardCharsets.UTF_8);
            var headers=delivery.getProps().getHeaders();
            int attempt=headers!=null && headers.get("yueji-attempt") instanceof Number n?n.intValue():0;
            try {consumer.consume(body);}
            catch(RuntimeException failure) {
                String route=attempt>=3?"dead":"retry."+new int[]{10000,60000,300000}[Math.max(0,attempt)];
                try {sender.send(body,delivery.getProps().getMessageId(),route,attempt+1);}
                catch(RuntimeException unknown) {channel.basicNack(tag,false,true);throw unknown;}
                metrics.counter("yueji.event.delivery","result",attempt>=3?"dead":"retry").increment();
            }
            // 业务事务提交后才确认；确认丢失导致重送，由数据库唯一键消除副作用。
            channel.basicAck(tag,false);
            return true;
        }));
    }
}
