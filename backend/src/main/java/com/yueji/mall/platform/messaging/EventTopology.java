package com.yueji.mall.platform.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="yueji.messaging.enabled", havingValue="true")
public class EventTopology {
    @Bean
    Declarables eventQueues(@Value("${yueji.messaging.namespace:yueji.dev}") String prefix) {
        var items = new java.util.ArrayList<Declarable>();
        var exchange = new DirectExchange(prefix + ".events", true, false);
        var queue = new Queue(prefix + ".facts", true);
        var dead = new Queue(prefix + ".dead", true);
        items.add(exchange); items.add(queue); items.add(dead);
        items.add(BindingBuilder.bind(queue).to(exchange).with("facts"));
        items.add(BindingBuilder.bind(dead).to(exchange).with("dead"));
        for (int delay : new int[]{10000,60000,300000}) {
            var retry = QueueBuilder.durable(prefix + ".retry." + delay)
                    .ttl(delay).deadLetterExchange(prefix + ".events").deadLetterRoutingKey("facts").build();
            items.add(retry);
            items.add(BindingBuilder.bind(retry).to(exchange).with("retry." + delay));
        }
        return new Declarables(items);
    }
}
