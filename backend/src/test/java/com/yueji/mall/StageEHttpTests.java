package com.yueji.mall;

import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.payment.adapter.*;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.payment.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class StageEHttpTests extends StageEFixtures {
    @Autowired PaymentRepository payments;@Autowired PaymentFacts facts;@Autowired PaymentService service;@Autowired PaymentRecovery recovery;@Autowired LatePaymentCompensation compensation;
    @MockitoSpyBean LocalMockAdapter channel;
    String pay(String order) {return data(call("POST","/orders/"+order+"/payments",Map.of(),a,key()),201).path("id").asText();}
    Ticket ticket(String id) {return payments.ticket(payments.row(id));}
    void cancel(String order) {data(call("POST","/orders/"+order+"/cancel",Map.of("reason","测试取消"),a,key()),202);}
    void due(String id) {sql.update("UPDATE payment SET last_checked_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) WHERE id=?",id);}
    void check(String id) {due(id);service.check(ticket(id),"stage-e-test",false);}
    void invariant(String file) throws Exception {
        var data=new LinkedHashMap<String,Object>();
        data.put("stockBounds",sql.count("SELECT COUNT(*) FROM inventory WHERE reserved>on_hand"));
        data.put("reservationMismatch",sql.count("SELECT COUNT(*) FROM inventory i WHERE i.reserved<>(SELECT COALESCE(SUM(r.quantity),0) FROM inventory_reservation r WHERE r.sku_id=i.sku_id AND r.status='RESERVED')"));
        data.put("duplicatePayments",sql.count("SELECT COUNT(*) FROM (SELECT order_id FROM payment GROUP BY order_id HAVING COUNT(*)>1) x"));
        data.put("duplicateCompensation",sql.count("SELECT COUNT(*) FROM (SELECT payment_id FROM refund WHERE reason_type='LATE_PAYMENT_COMPENSATION' GROUP BY payment_id HAVING COUNT(*)>1) x"));
        data.put("duplicateMovement",sql.count("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) x"));
        data.put("cancelledWithCommit",sql.count("SELECT COUNT(*) FROM trade_order o JOIN inventory_reservation r ON r.order_id=o.id WHERE o.status='CANCELLED' AND r.status<>'RELEASED'"));
        for(var e:data.entrySet()) assertThat(((Number)e.getValue()).longValue()).as(e.getKey()).isZero();
        data.put("orders",sql.rows("SELECT status,financial_status,COUNT(*) count FROM trade_order GROUP BY status,financial_status"));
        data.put("payments",sql.rows("SELECT provider,status,COUNT(*) count FROM payment GROUP BY provider,status"));
        data.put("reservations",sql.rows("SELECT status,SUM(quantity) quantity FROM inventory_reservation GROUP BY status"));
        data.put("refunds",sql.rows("SELECT reason_type,status,COUNT(*) count FROM refund GROUP BY reason_type,status"));
        data.put("capturedAt",java.time.Instant.now().toString());data.put("database",sql.required("SELECT VERSION() version"));
        Files.writeString(Path.of("../.artifacts/"+file),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
    @Test void localPaymentTwentyReplaysAndConcurrentConfirmCommitOnlyOnce() throws Exception {
        String order=order(batch()),id=pay(order),key=key();
        var first=data(call("POST","/payments/"+id+"/mock-confirm",Map.of(),a,key),200);assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        for(int i=0;i<20;i++) assertThat(data(call("POST","/payments/"+id+"/mock-confirm",Map.of(),a,key),200)).isEqualTo(first);
        try(var pool=Executors.newFixedThreadPool(10)) {var jobs=new ArrayList<Future<?>>();for(int i=0;i<20;i++) jobs.add(pool.submit(()->assertThat(data(call("POST","/payments/"+id+"/mock-confirm",Map.of(),a,key()),200).path("status").asText()).isEqualTo("SUCCEEDED")));for(var job:jobs) job.get(30,TimeUnit.SECONDS);}
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='COMMIT'")).isEqualTo(1);
        assertThat(sql.required("SELECT on_hand,reserved FROM inventory WHERE sku_id=?",sku1)).containsEntry("on_hand",98L).containsEntry("reserved",0L);
        assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaid' AND status='PENDING'")).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM order_status_log WHERE to_status='PAID'")).isEqualTo(1);invariant("E-local-paid-invariants.json");
    }
    @Test void creationIsIdempotentAndEveryOrderHasItsOwnPaymentNumber() {
        var batch=batch();String one=order(batch),two=batch.path("orders").get(1).path("id").asText(),key=key();
        var first=data(call("POST","/orders/"+one+"/payments",Map.of(),a,key),201);
        for(int i=0;i<20;i++) assertThat(data(call("POST","/orders/"+one+"/payments",Map.of(),a,key),201)).isEqualTo(first);
        var existing=data(call("POST","/orders/"+one+"/payments",null,a,key()),200);assertThat(existing.path("paymentNo")).isEqualTo(first.path("paymentNo"));
        error(call("POST","/orders/"+two+"/payments",null,a,key),409,"IDEMPOTENCY_CONFLICT");
        var second=data(call("POST","/orders/"+two+"/payments",null,a,key()),201);assertThat(second.path("paymentNo")).isNotEqualTo(first.path("paymentNo"));
        assertThat(sql.count("SELECT COUNT(*) FROM payment")).isEqualTo(2);assertThat(sql.count("SELECT COUNT(*) FROM idempotency_record WHERE operation_id='API-32' AND resource_id IS NULL")).isZero();
        assertThat(first.path("amount").asText()).isEqualTo("39.80");assertThat(first.path("paymentAction").path("type").asText()).isEqualTo("MOCK");
    }
    @Test void ownershipPermissionsAndClientOverridesCannotChoosePaymentFacts() {
        String order=order(batch()),id=pay(order);var admin=buyer("e_admin","ADMIN");
        error(call("POST","/payments/"+id+"/mock-confirm",null,admin,key()),403,"ACCESS_DENIED");
        error(call("POST","/orders/"+order+"/payments",null,b,key()),404,"RESOURCE_NOT_FOUND");
        error(call("GET","/payments/"+id,null,b,null),404,"RESOURCE_NOT_FOUND");error(call("POST","/payments/"+id+"/mock-confirm",null,b,key()),404,"RESOURCE_NOT_FOUND");
        error(call("GET","/payments/"+id,null,null,null),401,"AUTH_REQUIRED");
        error(call("POST","/orders/"+order+"/payments",Map.of("amount","0.01"),a,key()),400,"PARAM_INVALID");
        error(call("POST","/payments/"+id+"/mock-confirm",Map.of("status","SUCCEEDED"),a,key()),400,"PARAM_INVALID");
        error(call("POST","/orders/"+order+"/payments",null,a,"short"),400,"PARAM_INVALID");
        error(call("GET","/payments/0",null,a,null),400,"PARAM_INVALID");
        assertThat(sql.count("SELECT COUNT(*) FROM payment WHERE status='SUCCEEDED'")).isZero();
    }
    @Test void unstartedCancelledOrderReleasesOnlyItsOwnReservation() throws Exception {
        String order=order(batch());cancel(order);recovery.scan("test-e-owner");recovery.scan("test-e-owner");
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("CANCELLED");
        assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(1);assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='RELEASE'")).isEqualTo(1);
        error(call("POST","/orders/"+order+"/payments",null,a,key()),409,"STATE_CONFLICT");invariant("E-local-closed-invariants.json");
    }
    @Test void paidFactBeforeCrashIsRecoveredUsingOriginalNumber() throws Exception {
        String order=order(batch()),id=pay(order);var original=ticket(id);channel.confirm(original);
        assertThat(payments.row(id).get("status")).isEqualTo("PENDING");
        check(id);check(id);assertThat(payments.row(id).get("status")).isEqualTo("SUCCEEDED");assertThat(ticket(id).paymentNo()).isEqualTo(original.paymentNo());
        assertThat(sql.count("SELECT COUNT(*) FROM payment")).isEqualTo(1);invariant("E-local-recovery-invariants.json");
    }
    @Test void channelIsOutsideTransactionAndUnknownKeepsClosingAndStock() {
        String order=order(batch()),id=pay(order);cancel(order);
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);}).when(channel).query(any());
        check(id);verify(channel,atLeastOnce()).query(any());
        assertThat(payments.row(id).get("failure_code")).isEqualTo("RETRY_1");assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(3);
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("CLOSING");
        reset(channel);check(id);assertThat(payments.row(id).get("status")).isEqualTo("CLOSED");
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("CANCELLED");
    }
    @Test void closingPaidWinsOrAlreadyClosedRejectsMockPaymentWithoutRevival() throws Exception {
        String order=order(batch()),id=pay(order);cancel(order);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var paid=pool.submit(()->call("POST","/payments/"+id+"/mock-confirm",null,a,key()));var close=pool.submit(()->{check(id);return true;});
            int code=paid.get(30,TimeUnit.SECONDS).getStatusCode().value();assertThat(code).isIn(200,409);close.get(30,TimeUnit.SECONDS);
        }
        check(id);String state=sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status").toString();assertThat(state).isIn("PAID","CANCELLED");
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status=?",order,state.equals("PAID")?"COMMITTED":"RELEASED")).isEqualTo(1);invariant("E-local-race-invariants.json");
    }
    @Test void failedLocalTransactionDoesNotLoseDurableChannelSuccess() {
        String order=order(batch()),id=pay(order);channel.confirm(ticket(id));
        sql.update("CREATE TRIGGER e_test_paid_outbox BEFORE INSERT ON outbox_event FOR EACH ROW BEGIN IF NEW.event_type='OrderPaid' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rollback'; END IF; END");
        try {assertThatThrownBy(()->check(id)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(payments.row(id).get("status")).isEqualTo("PENDING");assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='COMMIT'")).isZero();}
        finally {sql.update("DROP TRIGGER e_test_paid_outbox");}
        check(id);assertThat(payments.row(id).get("status")).isEqualTo("SUCCEEDED");
    }
    @Test void latePaymentCreatesOneCompensationAndNeverReacquiresReleasedStock() throws Exception {
        String order=order(batch()),id=pay(order);cancel(order);check(id);var ticket=ticket(id);
        // 隔离测试夹具模拟极端渠道迟到成功，生产没有这样的状态修改入口。
        sql.update("INSERT INTO payment_event(payment_id,provider,event_key,event_type,verified,payload_digest,trace_id) VALUES(?,'LOCAL_MOCK',?,'MOCK_CHANNEL',1,?,'test-late')",id,"mock:"+ticket.paymentNo()+":paid",Crypto.hash("late"));
        var fact=channel.query(ticket);for(int i=0;i<20;i++) facts.apply(id,fact,"late:"+i,Crypto.hash("late:"+i),"test-late");
        assertThat(sql.count("SELECT COUNT(*) FROM refund")).isEqualTo(1);assertThat(sql.required("SELECT financial_status FROM trade_order WHERE id=?",order).get("financial_status")).isEqualTo("PAID");
        String refund=sql.required("SELECT id FROM refund").get("id").toString();compensation.process(refund,"test-compensation");compensation.process(refund,"test-compensation");
        assertThat(sql.required("SELECT status,financial_status FROM trade_order WHERE id=?",order)).containsEntry("status","CANCELLED").containsEntry("financial_status","REFUNDED");
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='COMMIT'")).isZero();assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundSucceeded'")).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE issue_key=? AND status='RESOLVED'","late:"+id)).isEqualTo(1);invariant("E-local-late-compensation.json");
    }
    @Test void amountAndChannelTradeMismatchAreRecordedWithoutChangingOrder() {
        String order=order(batch()),id=pay(order);var t=ticket(id);
        assertThat(facts.apply(id,new Fact(FactStatus.PAID,t.paymentNo(),"trade-bad",new java.math.BigDecimal("0.01"),null),"bad-amount",Crypto.hash("bad"),"test-bad")).isFalse();
        assertThat(facts.apply(id,new Fact(FactStatus.PAID,"wrong-no","trade-bad",t.amount(),null),"bad-no",Crypto.hash("bad"),"test-bad")).isFalse();
        assertThat(facts.apply(id,new Fact(FactStatus.PAID,t.paymentNo(),null,t.amount(),null),"bad-trade",Crypto.hash("bad"),"test-bad")).isFalse();
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_issue")).isEqualTo(3);assertThat(payments.row(id).get("status")).isEqualTo("PENDING");
        var good=channel.confirm(t);facts.apply(id,good,"good",Crypto.hash("good"),"test-good");
        assertThat(facts.apply(id,good,"good",Crypto.hash("changed"),"test-good")).isFalse();
        facts.apply(id,new Fact(FactStatus.CLOSED,t.paymentNo(),good.tradeNo(),t.amount(),null),"out-of-order",Crypto.hash("closed"),"test-order");assertThat(payments.row(id).get("status")).isEqualTo("SUCCEEDED");
    }
    @Test void neverConfirmedNotFoundAndBackoffDoNotReleaseReservation() {
        String order=order(batch()),id=pay(order);cancel(order);doReturn(new Fact(FactStatus.NOT_FOUND,ticket(id).paymentNo(),null,null,null)).when(channel).query(any());
        check(id);service.check(ticket(id),"test-backoff",false);verify(channel,times(2)).query(any()); // create 中查过一次，只有强制到期后的查询再调用
        assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(3);assertThat(payments.row(id).get("status")).isEqualTo("PENDING");
        sql.update("INSERT INTO order_status_log(order_id,from_status,to_status,action,reason,trace_id,created_at) VALUES(?,'PENDING_PAYMENT','CLOSING','TEST_AGE','虚构测试','test-age',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 16 MINUTE))",order);
        assertThat(recovery.unresolved()).isEqualTo(1);recovery.scan("test-age-owner");assertThat(recovery.scan("other-owner")).isZero();
    }
    @Test void closedChannelFactBeforeExpiryStillConvergesAndCannotBePaidAgain() {
        String order=order(batch()),id=pay(order);var t=ticket(id);facts.apply(id,new Fact(FactStatus.CLOSED,t.paymentNo(),null,t.amount(),null),"early-close",Crypto.hash("close"),"test-close");
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("CANCELLED");
        error(call("POST","/payments/"+id+"/mock-confirm",null,a,key()),409,"STATE_CONFLICT");
    }
    @Test void paymentRequestTimeoutPreservesNumberAndDoesNotPretendAcceptedChannelAction() {
        String order=order(batch()),key=key();doThrow(new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE)).when(channel).query(any());
        error(call("POST","/orders/"+order+"/payments",null,a,key),502,"CHANNEL_UNAVAILABLE");
        String id=sql.required("SELECT id FROM payment WHERE order_id=?",order).get("id").toString(),no=ticket(id).paymentNo();
        error(call("POST","/orders/"+order+"/payments",null,a,key),502,"CHANNEL_UNAVAILABLE");
        assertThat(sql.count("SELECT COUNT(*) FROM payment")).isEqualTo(1);reset(channel);due(id);
        var recovered=data(call("POST","/orders/"+order+"/payments",null,a,key),201);assertThat(recovered.path("paymentNo").asText()).isEqualTo(no);assertThat(recovered.path("status").asText()).isEqualTo("PENDING");
    }
}
