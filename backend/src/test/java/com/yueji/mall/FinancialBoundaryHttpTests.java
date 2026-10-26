package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class FinancialBoundaryHttpTests extends StageFFixtures {
    @org.springframework.beans.factory.annotation.Autowired com.yueji.mall.payment.service.RefundService refunds;
    @org.springframework.beans.factory.annotation.Autowired com.yueji.mall.payment.service.LatePaymentCompensation compensation;
    @Test void financialQueriesRejectInvalidFiltersInsteadOfChangingScope() {
        for(String path:List.of("/finance/refunds","/finance/reconciliation-issues","/finance/settlements","/finance/settlement-adjustments")) {
            error(call("GET",path+"?status=INVALID",null,finance,null),400,"PARAM_INVALID");
            error(call("GET",path+"?sort=UNSUPPORTED",null,finance,null),400,"PARAM_INVALID");
            data(call("GET",path,null,finance,null),200);
        }
        error(call("GET","/finance/reconciliations?sort=INVALID",null,finance,null),400,"PARAM_INVALID");
        for(String path:List.of("/finance/refunds","/finance/settlements","/finance/settlement-adjustments"))
            error(call("GET",path+"?shopId=invalid",null,finance,null),400,"PARAM_INVALID");
        for(String path:List.of("/merchant/shops/"+shop1+"/settlements","/merchant/shops/"+shop1+"/after-sales")) {
            error(call("GET",path+"?sort=INVALID",null,merchant,null),400,"PARAM_INVALID");
            error(call("GET",path+"?status=INVALID",null,merchant,null),400,"PARAM_INVALID");
        }
        error(call("GET","/support/after-sales?shopId=invalid",null,support,null),400,"PARAM_INVALID");
        error(call("GET","/support/after-sales?sort=INVALID",null,support,null),400,"PARAM_INVALID");
        error(call("GET","/after-sales?sort=INVALID",null,a,null),400,"PARAM_INVALID");
        error(call("POST","/finance/reconciliations",Map.of("billDate",LocalDate.now().toString(),"provider","LOCAL_MOCK"),finance,key()),400,"PARAM_INVALID");
        assertThat(sql.count("SELECT COUNT(*) FROM settlement")).isZero();
    }
    @Test void remainingPublicAndSupportContractsExerciseRejectionsAndAuthorizedSuccess() {
        var headers=new org.springframework.http.HttpHeaders();headers.setBearerAuth("invalid-access-token");
        var response=client.exchange("/api/v1/categories",org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),JsonNode.class);
        error(response,401,"AUTH_REQUIRED");
        error(call("GET","/products/0/reviews",null,null,null),400,"PARAM_INVALID");
        String order=firstOrder(batch());pay(order);String sale=data(call("POST","/after-sales",Map.of("orderItemId",orderItem(order),"type","REFUND_ONLY","quantity",1,"reason","客服详情权限"),a,key()),201).path("id").asText();
        assertThat(data(call("GET","/support/after-sales/"+sale,null,support,null),200).path("id").asText()).isEqualTo(sale);
        error(call("GET","/support/after-sales/"+sale,null,a,null),403,"ACCESS_DENIED");
        data(call("GET","/merchant/shops/"+shop1+"/orders?status=PAID",null,warehouse,null),200);
        for(String query:List.of("sort=INVALID","status=INVALID","orderNo=","orderNo="+"x".repeat(41)))
            error(call("GET","/merchant/shops/"+shop1+"/orders?"+query,null,merchant,null),400,"PARAM_INVALID");
    }
    @Test void reviewModerationIsAuditedOnceAndFilteringNeverExpandsScope() {
        String order=firstOrder(batch());pay(order);ship(order,shop1);receive(order);
        String item=orderItem(order);
        for(String content:List.of("<script>","unsafe>content"))
            error(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content",content),a,null),400,"PARAM_INVALID");
        JsonNode review=data(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content","真实纯文本评价"),a,null),201);
        String id=review.path("id").asText();String product=review.path("productId").asText();
        var body=Map.of("status","HIDDEN","reason","测试审核隐藏");
        data(call("PUT","/ops/reviews/"+id+"/visibility",body,operator,null),200);
        data(call("PUT","/ops/reviews/"+id+"/visibility",body,operator,null),200);
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='review.visibility' AND resource_id=?",id)).isEqualTo(1);
        assertThat(data(call("GET","/ops/reviews?productId="+product+"&status=HIDDEN",null,operator,null),200).path("total").asInt()).isEqualTo(1);
        for(String query:List.of("sort=INVALID","productId="+product+"&status=INVALID","productId=invalid","status=INVALID"))
            error(call("GET","/ops/reviews?"+query,null,operator,null),400,"PARAM_INVALID");
    }
    @Test void rejectedSettlementCanBeRegeneratedAndStaleApprovalCannotMutateIt() {
        String order=completedEligibleOrder();LocalDate date=LocalDate.now().minusDays(1);
        JsonNode draft=statement(date);String id=draft.path("id").asText();
        error(call("POST","/finance/settlements/"+id+"/mock-payout",Map.of("version",draft.path("version").asLong()),financeApprover,key()),409,"SETTLEMENT_BLOCKED");
        error(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",true,"note","旧版本","version",1),financeApprover,key()),409,"VERSION_CONFLICT");
        JsonNode rejected=data(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",false,"note","要求复核","version",draft.path("version").asLong()),financeApprover,key()),200);
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        data(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",false,"note","重复拒绝","version",rejected.path("version").asLong()),financeApprover,key()),200);
        assertThat(sql.count("SELECT COUNT(*) FROM settlement_item WHERE settlement_id=?",id)).isZero();
        JsonNode recreated=statement(date);assertThat(recreated.path("status").asText()).isEqualTo("DRAFT");
        assertThat(sql.count("SELECT COUNT(*) FROM settlement_item WHERE order_id=?",order)).isEqualTo(1);
    }
    @Test void settledOrderIsRevalidatedBeforeApprovalAndNoSnapshotCanHideNewDifference() {
        String order=completedEligibleOrder();JsonNode draft=statement(LocalDate.now().minusDays(1));String id=draft.path("id").asText();
        String issue=sql.insert("INSERT INTO reconciliation_issue(order_id,issue_key,issue_type) VALUES(?,?,'TEST_DIFFERENCE')",order,"boundary-"+key());
        error(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",true,"note","开放差异阻止批准","version",draft.path("version").asLong()),financeApprover,key()),409,"SETTLEMENT_BLOCKED");
        assertThat(sql.required("SELECT status FROM settlement WHERE id=?",id).get("status")).isEqualTo("DRAFT");
        data(call("POST","/finance/reconciliation-issues/"+issue+"/resolve",Map.of("resolution","已核对渠道","evidenceReference","LOCAL_MOCK:verified"),finance,null),200);
        JsonNode approved=data(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",true,"note","独立核对","version",draft.path("version").asLong()),financeApprover,key()),200);
        data(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",true,"note","重复审批","version",approved.path("version").asLong()),financeApprover,key()),200);
        error(call("POST","/finance/settlements/"+id+"/decision",Map.of("approved",false,"note","不得反向改审批","version",approved.path("version").asLong()),financeApprover,key()),409,"STATE_CONFLICT");
        assertThat(data(call("GET","/finance/settlements/"+id,null,finance,null),200).path("payableAmount").asText()).isEqualTo("38.61");
    }
    @Test void refundWrongChannelIdentityCreatesDifferenceAndNeverRestocks() {
        String order=firstOrder(batch());pay(order);String refund=approvedRefund(order);
        doAnswer(invocation->new RefundFact(RefundStatus.SUCCEEDED,"wrong-refund","wrong-payment",new BigDecimal("19.90"),"mock-channel")).when(channel).refund(any(),anyString(),any());
        JsonNode result=data(call("POST","/finance/refunds/"+refund+"/execute",null,finance,key()),202);
        assertThat(result.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE refund_id=? AND status='OPEN'",refund)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refund)).isZero();
        error(call("POST","/finance/refunds/"+refund+"/execute",null,finance,key()),409,"REQUEST_IN_PROGRESS");
        assertThat(sql.required("SELECT refunded_amount FROM trade_order WHERE id=?",order).get("refunded_amount")).isEqualTo(new BigDecimal("0.00"));
    }
    @Test void reconciliationCanResumeFailedRunAndKeepsRunningRunIdStable() {
        LocalDate date=LocalDate.now().minusDays(3);
        String id=sql.insert("INSERT INTO reconciliation_run(provider,bill_date,status) VALUES('LOCAL_MOCK',?,'RUNNING')",date);
        var body=Map.of("billDate",date.toString(),"provider","LOCAL_MOCK");
        assertThat(data(call("POST","/finance/reconciliations",body,finance,key()),202).path("id").asText()).isEqualTo(id);
        sql.update("UPDATE reconciliation_run SET status='FAILED' WHERE id=?",id);
        JsonNode rerun=data(call("POST","/finance/reconciliations",body,finance,key()),202);
        assertThat(rerun.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(data(call("POST","/finance/reconciliations",body,finance,key()),202).path("id").asText()).isEqualTo(id);
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_run")).isEqualTo(1);
    }
    @Test void completedReturnRejectsDifferentSecondParcelAndDuplicateDecision() {
        String order=firstOrder(batch());pay(order);ship(order,shop1);receive(order);
        String sale=data(call("POST","/after-sales",Map.of("orderItemId",orderItem(order),"type","RETURN_REFUND","quantity",1,"reason","退货边界"),a,key()),201).path("id").asText();
        var decision=Map.of("approved",true,"note","批准退货");
        data(call("POST","/support/after-sales/"+sale+"/decision",decision,support,null),200);
        data(call("POST","/support/after-sales/"+sale+"/decision",decision,support,null),200);
        error(call("POST","/support/after-sales/"+sale+"/decision",Map.of("approved",false,"note","相反决定"),support,null),409,"STATE_CONFLICT");
        var parcel=Map.of("carrier","测试承运商","trackingNo","RETURN-BOUNDARY");
        data(call("PUT","/after-sales/"+sale+"/return-shipment",parcel,a,null),200);
        data(call("PUT","/after-sales/"+sale+"/return-shipment",parcel,a,null),200);
        error(call("PUT","/after-sales/"+sale+"/return-shipment",Map.of("carrier","另一个承运商","trackingNo","SECOND-PARCEL"),a,null),409,"STATE_CONFLICT");
        error(call("PUT","/after-sales/"+sale+"/return-shipment",parcel,b,null),404,"RESOURCE_NOT_FOUND");
        error(call("POST","/after-sales/"+sale+"/cancel",null,a,null),409,"STATE_CONFLICT");
        assertThat(data(call("GET","/support/after-sales?shopId="+shop1+"&status=RETURNING",null,support,null),200).path("total").asLong()).isEqualTo(1);
    }
    @Test void refundOnlyCannotBeRequestedAfterShipmentAndReturnRequiresReceipt() {
        String order=firstOrder(batch());pay(order);String item=orderItem(order);ship(order,shop1);
        for(String type:List.of("REFUND_ONLY","RETURN_REFUND"))
            error(call("POST","/after-sales",Map.of("orderItemId",item,"type",type,"quantity",1,"reason","不适用状态"),a,key()),409,"STATE_CONFLICT");
        assertThat(sql.count("SELECT COUNT(*) FROM after_sale")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM refund")).isZero();
    }
    private String approvedRefund(String order) {
        String sale=data(call("POST","/after-sales",Map.of("orderItemId",orderItem(order),"type","REFUND_ONLY","quantity",1,"reason","测试退款边界"),a,key()),201).path("id").asText();
        return data(call("POST","/support/after-sales/"+sale+"/decision",Map.of("approved",true,"note","独立审核"),support,null),200).path("refund").path("id").asText();
    }
    private String completedEligibleOrder() {
        String order=firstOrder(batch());pay(order);ship(order,shop1);receive(order);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) WHERE id=?",order);return order;
    }
    private JsonNode statement(LocalDate date) {return data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",date.toString()),finance,key()),201);}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"normal:refund","normal:payment","normal:null","normal:amount","normal:unknown","normal:not-found","late:refund","late:payment","late:null","late:amount","late:unknown","late:not-found"})
    void reconciliationQuarantinesUnverifiedRefundFactsWithoutChangingStock(String variant) {
        String order=firstOrder(batch());pay(order);boolean late=variant.startsWith("late:");
        var payment=sql.required("SELECT * FROM payment WHERE order_id=?",order);
        String refund;
        if(late) {
            sql.update("UPDATE trade_order SET status='CANCELLED' WHERE id=?",order);
            refund=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,'PROCESSING')","RL"+key(),payment.get("id"),payment.get("amount"));
        } else refund=approvedRefund(order);
        LocalDate date=LocalDate.now().minusDays(4);
        var created=java.sql.Timestamp.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());
        sql.update("UPDATE refund SET status='PROCESSING',created_at=? WHERE id=?",created,refund);
        var stored=sql.required("SELECT * FROM refund WHERE id=?",refund);
        String no=stored.get("refund_no").toString(), paymentNo=payment.get("payment_no").toString();
        String kind=variant.split(":")[1];
        RefundStatus status=kind.equals("unknown")?RefundStatus.UNKNOWN:kind.equals("not-found")?RefundStatus.NOT_FOUND:RefundStatus.SUCCEEDED;
        BigDecimal amount=kind.equals("null")?null:kind.equals("amount")?new BigDecimal("0.01"):(BigDecimal)stored.get("amount");
        RefundFact invalid=new RefundFact(status,kind.equals("refund")?"wrong-refund":no,kind.equals("payment")?"wrong-payment":paymentNo,amount,"mock-evidence");
        doReturn(invalid).when(channel).queryRefund(any(),eq(no),any());
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("differenceCount").asInt()).isEqualTo(1);
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refund).get("status")).isEqualTo("PROCESSING");
        assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(98);
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE refund_id=? AND status='OPEN'",refund)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status","payment","refund","null","amount"})
    void refundSuccessServiceRejectsUnverifiedIdentityAmountAndStatus(String variant) {
        String order=firstOrder(batch());pay(order);String refund=approvedRefund(order);
        var stored=sql.required("SELECT r.*,p.payment_no FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refund);
        sql.update("UPDATE refund SET status='PROCESSING',requested_by=? WHERE id=?",finance.id(),refund);
        RefundFact fact=new RefundFact(variant.equals("status")?RefundStatus.UNKNOWN:RefundStatus.SUCCEEDED,
                variant.equals("refund")?"other-refund":stored.get("refund_no").toString(),variant.equals("payment")?"other-payment":stored.get("payment_no").toString(),
                variant.equals("null")?null:variant.equals("amount")?BigDecimal.ONE:(BigDecimal)stored.get("amount"),"mock-channel");
        assertThatThrownBy(()->refunds.reconcileSuccess(refund,fact,"boundary-trace")).isInstanceOf(com.yueji.mall.common.api.BusinessException.class);
        assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(98);
        assertThat(sql.required("SELECT refunded_amount FROM trade_order WHERE id=?",order).get("refunded_amount")).isEqualTo(new BigDecimal("0.00"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CREATED","FAILED","PROCESSING"})
    void lateCompensationRecoversOriginalRefundNumberAndNeverRestocksCancelledOrder(String status) {
        String order=firstOrder(batch());pay(order);
        var p=sql.required("SELECT * FROM payment WHERE order_id=?",order);
        sql.update("UPDATE trade_order SET status='CANCELLED' WHERE id=?",order);
        String no="RL"+key();String refund=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status,updated_at) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,?,DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 31 SECOND))",no,p.get("id"),p.get("amount"),status);
        compensation.process(refund,"late-boundary-trace");compensation.process(refund,"late-repeat-trace");
        assertThat(sql.required("SELECT refund_no,status FROM refund WHERE id=?",refund)).containsEntry("refund_no",no).containsEntry("status","SUCCEEDED");
        assertThat(sql.required("SELECT status,financial_status FROM trade_order WHERE id=?",order)).containsEntry("status","CANCELLED").containsEntry("financial_status","REFUNDED");
        assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(98);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refund)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status","payment","refund","null","amount"})
    void lateRefundSuccessRejectsUnverifiedChannelFacts(String variant) {
        String order=firstOrder(batch());pay(order);var p=sql.required("SELECT * FROM payment WHERE order_id=?",order);
        sql.update("UPDATE trade_order SET status='CANCELLED' WHERE id=?",order);
        String no="RL"+key(), id=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,'PROCESSING')",no,p.get("id"),p.get("amount"));
        var invalid=new RefundFact(variant.equals("status")?RefundStatus.UNKNOWN:RefundStatus.SUCCEEDED,variant.equals("refund")?"other":no,
                variant.equals("payment")?"other":p.get("payment_no").toString(),variant.equals("null")?null:variant.equals("amount")?BigDecimal.ONE:(BigDecimal)p.get("amount"),"mock-proof");
        assertThatThrownBy(()->compensation.reconcileSuccess(id,invalid,"late-validation-trace")).isInstanceOf(com.yueji.mall.common.api.BusinessException.class);
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",id).get("status")).isEqualTo("PROCESSING");
        assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(98);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"unknown","payment","refund","null","amount","channel-error"})
    void lateRefundSubmissionUnknownDoesNotPretendSuccess(String variant) {
        String order=firstOrder(batch());pay(order);var p=sql.required("SELECT * FROM payment WHERE order_id=?",order);
        sql.update("UPDATE trade_order SET status='CANCELLED' WHERE id=?",order);
        String no="RL"+key(), id=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,'CREATED')",no,p.get("id"),p.get("amount"));
        var invalid=new RefundFact(variant.equals("unknown")?RefundStatus.UNKNOWN:RefundStatus.SUCCEEDED,variant.equals("refund")?"other":no,
                variant.equals("payment")?"other":p.get("payment_no").toString(),variant.equals("null")?null:variant.equals("amount")?BigDecimal.ONE:(BigDecimal)p.get("amount"),"mock-proof");
        if(variant.equals("channel-error"))doThrow(new com.yueji.mall.common.api.BusinessException(com.yueji.mall.common.api.ErrorCode.CHANNEL_UNAVAILABLE)).when(channel).refund(any(),eq(no),any());
        else doReturn(invalid).when(channel).refund(any(),eq(no),any());
        compensation.process(id,"late-unknown-trace");compensation.process(id,"late-too-soon-trace");
        assertThat(sql.required("SELECT status,failure_code FROM refund WHERE id=?",id)).containsEntry("status","PROCESSING").containsEntry("failure_code","CHANNEL_UNKNOWN");
        assertThat(sql.required("SELECT financial_status FROM trade_order WHERE id=?",order).get("financial_status")).isEqualTo("PAID");
        verify(channel,times(1)).refund(any(),eq(no),any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"payment","refund","unlinked"})
    void issueResolutionFindsOrderThroughPaymentOrRefundAndPreservesEvidence(String link) {
        String order=firstOrder(batch());pay(order);String refund=approvedRefund(order);String payment=sql.required("SELECT id FROM payment WHERE order_id=?",order).get("id").toString();
        String issue=sql.insert("INSERT INTO reconciliation_issue(payment_id,refund_id,issue_key,issue_type) VALUES(?,?,?,'CHANNEL_REVIEW')",link.equals("payment")?payment:null,link.equals("refund")?refund:null,"linked-"+key());
        var resolved=data(call("POST","/finance/reconciliation-issues/"+issue+"/resolve",Map.of("resolution","核对完成保留原账","evidenceReference","LOCAL_MOCK:channel-fact"),finance,null),200);
        assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.path("resolution").asText()).contains("LOCAL_MOCK:channel-fact");
        assertThat(sql.required("SELECT refunded_amount FROM trade_order WHERE id=?",order).get("refunded_amount")).isEqualTo(new BigDecimal("0.00"));
    }
}
