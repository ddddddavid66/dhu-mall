package com.yueji.mall;

import com.yueji.mall.platform.messaging.*;
import com.yueji.mall.platform.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK",
        "yueji.messaging.enabled=true","yueji.messaging.namespace=yueji.integration"})
class MessagingIntegrationTests extends StageEFixtures {
    static final TcpFaultProxy proxy=new TcpFaultProxy(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT","15673")));
    @org.springframework.test.context.DynamicPropertySource
    static void proxyPort(org.springframework.test.context.DynamicPropertyRegistry registry) {registry.add("spring.rabbitmq.port",proxy::port);}
    @Autowired OutboxDispatchService dispatch;
    @Autowired EventFactConsumer consumer;
    @Autowired ConfirmedEventSender sender;
    @Autowired EventWorker worker;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired DeadLetterReplay replay;
    @BeforeEach void isolatedQueues() {
        admin.initialize();
        for(String suffix:List.of("facts","dead","retry.10000","retry.60000","retry.300000")) admin.purgeQueue("yueji.integration."+suffix,false);
    }
    @Test void confirmedPublicationDuplicateAndOutOfOrderDeliveryHaveOneSideEffect() {
        batch();
        var events=sql.rows("SELECT * FROM outbox_event ORDER BY id DESC");
        assertThat(dispatch.dispatch("integration-a")).isEqualTo(2);
        assertThat(dispatch.dispatch("integration-b")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'")).isEqualTo(2);
        while(worker.consumeOne()) {}
        for(var event:events) sender.send(event.get("payload").toString(),event.get("event_id").toString(),"facts",0);
        while(worker.consumeOne()) {}
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='event.fact_observed'")).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM trade_order")).isEqualTo(2);
        assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(3);
    }
    @Test void lostConfirmReplaysOriginalEventAndUnknownRouteStaysPending() {
        batch();
        var event=sql.required("SELECT * FROM outbox_event ORDER BY id LIMIT 1");
        // 模拟Broker已接收、进程尚未标记PUBLISHED便崩溃的持久边界。
        sender.send(event.get("payload").toString(),event.get("event_id").toString(),"facts",0);
        assertThat(dispatch.dispatch("after-crash")).isEqualTo(2);
        while(worker.consumeOne()) {}
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='event.fact_observed'")).isEqualTo(2);
        assertThatThrownBy(()->sender.send(event.get("payload").toString(),event.get("event_id").toString(),"not-bound",0)).isInstanceOf(IllegalStateException.class);
    }
    @Test void failedConsumerTransactionRollsBackDedupAndCanBeRedelivered() {
        batch();var event=sql.required("SELECT * FROM outbox_event ORDER BY id LIMIT 1");String body=event.get("payload").toString();
        assertThatThrownBy(()->transactions.run(()->{consumer.consume(body);throw new IllegalStateException("模拟提交前崩溃");})).isInstanceOf(IllegalStateException.class);
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='event.fact_observed'")).isZero();
        assertThat(consumer.consume(body)).isTrue();
        assertThat(consumer.consume(body)).isFalse();
    }
    @Test void boundedRetryReachesDeadQueueWithoutAcknowledgingUnconfirmedCopies() {
        sender.send("{\"eventId\":\"missing-fact\"}","missing-fact","facts",3);
        assertThat(worker.consumeOne()).isTrue();
        assertThat(rabbit.receive("yueji.integration.facts")).isNull();
        var dead=rabbit.receive("yueji.integration.dead",3000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo("missing-fact");
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isZero();
    }
    @Test void expiredPublisherLeaseIsRecoverableAndConcurrentPublishersCannotLoseFacts() throws Exception {
        batch();sql.update("UPDATE outbox_event SET lease_owner='dead-process',lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND)");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->dispatch.dispatch("lease-a"));var b=pool.submit(()->dispatch.dispatch("lease-b"));
            assertThat(a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS)).isEqualTo(2);
        }
        while(worker.consumeOne()) {}
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isEqualTo(2);
    }
    @Test void brokerOutageKeepsDurablePendingFactsAndResumesAfterRecovery() {
        batch();
        var connection=(org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory();
        proxy.available(false);connection.resetConnection();
        try {
            assertThat(dispatch.dispatch("broker-down")).isZero();
            assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'")).isEqualTo(2);
            assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(3);
            assertThat(sql.count("SELECT MAX(attempts) FROM outbox_event")).isEqualTo(1);
        } finally {proxy.available(true);connection.resetConnection();}
        sql.update("UPDATE outbox_event SET next_attempt_at=UTC_TIMESTAMP(3)");
        assertThat(dispatch.dispatch("broker-recovered")).isEqualTo(2);
        while(worker.consumeOne()) {}
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isEqualTo(2);
    }
    @Test void persistentFailuresBackOffAndEventuallyRequireAuditedOperatorReplay() {
        batch();
        var connection=(org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory();
        proxy.available(false);connection.resetConnection();
        try {
            for(int previous:new int[]{1,2,10}) {
                sql.update("UPDATE outbox_event SET attempts=?,next_attempt_at=UTC_TIMESTAMP(3),lease_until=NULL",previous);
                dispatch.dispatch("failed-owner");
                assertThat(sql.count("SELECT MAX(attempts) FROM outbox_event")).isEqualTo(previous+1);
            }
            assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'")).isEqualTo(1);
            assertThat(sql.count("SELECT COUNT(*) FROM consumed_event")).isZero();
        } finally {proxy.available(true);connection.resetConnection();}
    }
    @Test void deadLetterReplayRequiresAdminPermissionExpectedEventAndKeepsOriginalId() {
        batch();var event=sql.required("SELECT * FROM outbox_event ORDER BY id LIMIT 1");String id=event.get("event_id").toString();
        sender.send(event.get("payload").toString(),id,"dead",4);
        var adminUser=buyer("replay_admin","ADMIN");var actor=tokens.authenticate(adminUser.token());
        assertThatThrownBy(()->replay.replay(tokens.authenticate(a.token()),id,"未经授权重放")).isInstanceOf(com.yueji.mall.common.api.BusinessException.class);
        assertThatThrownBy(()->replay.replay(actor,"wrong-event","审批编号不符"))
                .isInstanceOf(org.springframework.amqp.UncategorizedAmqpException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(admin.getQueueProperties("yueji.integration.dead").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isEqualTo(1);
        assertThat(replay.replay(actor,id,"已修复故障，按原事件重放")).isTrue();
        assertThat(worker.consumeOne()).isTrue();
        assertThat(sql.count("SELECT COUNT(*) FROM consumed_event WHERE event_id=?",id)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='event.dead_replay' AND resource_id=?",id)).isEqualTo(1);
    }
}
