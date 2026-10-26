package com.yueji.mall.platform.messaging;

import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.common.service.DatabaseTransaction;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 仅供受控运维命令调用，没有公开HTTP重放或万能状态修改接口。 */
@Service
public class DeadLetterReplay {
    private final RabbitTemplate rabbit;
    private final ConfirmedEventSender sender;
    private final IdentityPolicy policy;
    private final AuditRepository audit;
    private final DatabaseTransaction transactions;
    private final String queue;
    public DeadLetterReplay(RabbitTemplate rabbit,ConfirmedEventSender sender,IdentityPolicy policy,
            AuditRepository audit,DatabaseTransaction transactions,@Value("${yueji.messaging.namespace:yueji.dev}") String prefix) {
        this.rabbit=rabbit;this.sender=sender;this.policy=policy;this.audit=audit;this.transactions=transactions;this.queue=prefix+".dead";
    }
    public boolean replay(Actor actor,String expectedEventId,String reason) {
        policy.require(actor,"platform.audit.read");
        if(expectedEventId==null || !expectedEventId.matches("[A-Za-z0-9_-]{1,64}") || reason==null || reason.isBlank() || reason.length()>300)
            throw new com.yueji.mall.common.api.BusinessException(com.yueji.mall.common.api.ErrorCode.PARAM_INVALID);
        return Boolean.TRUE.equals(rabbit.execute(channel->{
            var delivery=channel.basicGet(queue,false);if(delivery==null) return false;
            long tag=delivery.getEnvelope().getDeliveryTag();
            if(!expectedEventId.equals(delivery.getProps().getMessageId())) {channel.basicNack(tag,false,true);throw new IllegalStateException("队首事件与审批编号不符，未重放");}
            try {
                sender.send(new String(delivery.getBody(),StandardCharsets.UTF_8),expectedEventId,"facts",0);
                transactions.run(()->{policy.require(actor,"platform.audit.read");audit.appendReason(actor.userId(),"event.dead_replay","event",expectedEventId,null,"SUCCESS",reason);return true;});
            } catch(RuntimeException failed) {channel.basicNack(tag,false,true);throw failed;}
            channel.basicAck(tag,false);return true;
        }));
    }
}
