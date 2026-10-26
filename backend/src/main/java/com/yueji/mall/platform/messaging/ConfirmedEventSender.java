package com.yueji.mall.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只有 Broker 确认且消息未退回才算投递成功；调用者必须在数据库事务外调用。 */
@Component
public class ConfirmedEventSender {
    private final RabbitTemplate rabbit;
    private final String exchange;
    public ConfirmedEventSender(RabbitTemplate rabbit, @Value("${yueji.messaging.namespace:yueji.dev}") String prefix) {
        this.rabbit = rabbit; this.exchange = prefix + ".events";
        rabbit.setMandatory(true);
    }
    public void send(String payload, String eventId, String route, int attempt) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        properties.setHeader("yueji-attempt", attempt);
        var correlation = new CorrelationData(eventId + ":" + UUID.randomUUID());
        try {
            rabbit.send(exchange, route, new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("事件未被确认路由到持久队列");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("事件确认被中断", error);
        } catch (Exception error) { throw new IllegalStateException("事件确认失败或未知", error); }
    }
}
