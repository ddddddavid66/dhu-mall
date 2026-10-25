package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.yueji.mall.payment.adapter.LocalMockAdapter;
import com.yueji.mall.payment.model.PaymentModels.Fact;
import com.yueji.mall.payment.model.PaymentModels.FactStatus;
import com.yueji.mall.payment.model.PaymentModels.RefundFact;
import com.yueji.mall.payment.model.PaymentModels.RefundStatus;
import com.yueji.mall.aftersales.service.AfterSaleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class StageFHttpTests extends StageEFixtures {
    @Autowired AfterSaleService afterSales;
    @MockitoSpyBean LocalMockAdapter channel;

    Buyer merchant, warehouse, support, supportFinance, finance, financeApprover, operator;

    @BeforeEach void stageFActors() {
        merchant=buyer("stage_f_merchant","MERCHANT");
        warehouse=buyer("stage_f_warehouse","WAREHOUSE");
        support=buyer("stage_f_support","SUPPORT");
        supportFinance=buyer("stage_f_support_finance","SUPPORT");
        sql.update("INSERT INTO iam_user_role(user_id,role_id) SELECT ?,id FROM iam_role WHERE code='FINANCE'",supportFinance.id());
        finance=buyer("stage_f_finance","FINANCE");
        financeApprover=buyer("stage_f_finance_approver","FINANCE");
        operator=buyer("stage_f_operator","OPERATOR");
        for(String shop:java.util.List.of(shop1,shop2)) {
            sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'MERCHANT')",shop,merchant.id());
            sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'WAREHOUSE')",shop,warehouse.id());
        }
    }

    @Test void tc15WholeShipmentManualEventsAndReceiptKeepOrderStateSeparate() {
        String orderId=firstOrder(batch());pay(orderId);
        JsonNode detail=data(call("GET","/merchant/shops/"+shop1+"/orders/"+orderId,null,merchant,null),200);
        assertThat(data(call("GET","/merchant/shops/"+shop1+"/orders?page=1&pageSize=20",null,merchant,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/merchant/shops/"+shop1+"/orders?status=PAID&orderNo="+detail.path("orderNo").asText(),null,merchant,null),200).path("total").asInt()).isEqualTo(1);
        error(call("GET","/merchant/shops/"+shop1+"/orders?status=INVALID",null,merchant,null),400,"PARAM_INVALID");
        String item=orderItem(orderId);
        String shipmentKey=key();var shipBody=Map.of("carrier","虚构承运商","trackingNo","F-TRACK-15","version",detail.path("version").asLong());
        JsonNode shipped=data(call("POST","/merchant/shops/"+shop1+"/orders/"+orderId+"/ship",shipBody,merchant,shipmentKey),201);
        assertThat(data(call("POST","/merchant/shops/"+shop1+"/orders/"+orderId+"/ship",shipBody,merchant,shipmentKey),201).path("id").asText()).isEqualTo(shipped.path("id").asText());
        assertThat(shipped.path("items").get(0).path("orderItemId").asText()).isEqualTo(item);
        assertThat(shipped.path("items").get(0).path("shippedQuantity").asInt()).isEqualTo(2);
        assertThat(shipped.path("events").get(0).path("source").asText()).isEqualTo("SYSTEM");
        error(call("POST","/merchant/shops/"+shop1+"/shipments/"+shipped.path("id").asText()+"/events",Map.of("eventTime",java.time.Instant.now().minusSeconds(60).toString(),"description","过早轨迹"),merchant,null),400,"PARAM_INVALID");
        JsonNode withEvent=data(call("POST","/merchant/shops/"+shop1+"/shipments/"+shipped.path("id").asText()+"/events",
                Map.of("eventTime",java.time.Instant.now().toString(),"description","虚构人工轨迹"),merchant,null),201);
        assertThat(withEvent.path("events").get(1).path("source").asText()).isEqualTo("MANUAL");
        String receiveKey=key();JsonNode received=data(call("POST","/orders/"+orderId+"/confirm-receipt",null,a,receiveKey),200);
        assertThat(received.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(received.path("shipment").path("receivedAt").isTextual()).isTrue();
        assertThat(data(call("POST","/orders/"+orderId+"/confirm-receipt",null,a,receiveKey),200).path("status").asText()).isEqualTo("COMPLETED");
        error(call("GET","/merchant/shops/"+shop1+"/orders/"+orderId,null,b,null),403,"ACCESS_DENIED");
        Buyer outsider=buyer("stage_f_warehouse_without_shop","WAREHOUSE");error(call("GET","/merchant/shops/"+shop1+"/orders",null,outsider,null),404,"RESOURCE_NOT_FOUND");
        assertThat(sql.count("SELECT shipped_quantity FROM shipment_item WHERE order_item_id=?",item)).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=?",orderId)).isEqualTo(1);
    }

    @Test void tc16Tc17UnshippedRefundIsReservedRetriedAndRestockedOnce() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        JsonNode apply=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","虚构部分退款"),a,key()),201);
        assertThat(data(call("GET","/after-sales?status=REQUESTED",null,a,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/support/after-sales?status=REQUESTED&shopId="+shop1,null,support,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/support/after-sales/"+apply.path("id").asText()+"/order",null,support,null),200).path("id").asText()).isEqualTo(orderId);
        assertThat(data(call("GET","/merchant/shops/"+shop1+"/after-sales?status=REQUESTED",null,merchant,null),200).path("total").asInt()).isEqualTo(1);
        error(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","重复申请"),a,key()),409,"STATE_CONFLICT");
        JsonNode order=data(call("GET","/merchant/shops/"+shop1+"/orders/"+orderId,null,merchant,null),200);
        error(call("POST","/merchant/shops/"+shop1+"/orders/"+orderId+"/ship",Map.of("carrier","虚构","trackingNo","blocked","version",order.path("version").asLong()),merchant,key()),409,"STATE_CONFLICT");
        JsonNode approved=data(call("POST","/support/after-sales/"+apply.path("id").asText()+"/decision",Map.of("approved",true,"note","同意虚构售后"),supportFinance,null),200);
        String refundId=approved.path("refund").path("id").asText();
        error(call("POST","/finance/refunds/"+refundId+"/execute",null,supportFinance,key()),403,"ACCESS_DENIED");
        var refund=sql.required("SELECT r.refund_no,p.payment_no,r.amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refundId);
        String refundNo=refund.get("refund_no").toString(),paymentNo=refund.get("payment_no").toString();BigDecimal amount=(BigDecimal)refund.get("amount");
        doReturn(new RefundFact(RefundStatus.FAILED,refundNo,paymentNo,amount,null)).when(channel).refund(any(),eq(refundNo),eq(amount));
        JsonNode failed=data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(data(call("GET","/finance/refunds?status=FAILED&shopId="+shop1,null,finance,null),200).path("total").asInt()).isEqualTo(1);
        error(call("GET","/finance/refunds?status=UNKNOWN",null,finance,null),400,"PARAM_INVALID");
        reset(channel);
        JsonNode succeeded=data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);
        assertThat(succeeded.path("status").asText()).isEqualTo("SUCCEEDED");
        data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refundId)).isEqualTo(1);
        assertThat(sql.required("SELECT on_hand FROM inventory WHERE sku_id=?",sku1).get("on_hand")).isEqualTo(99L);
        assertThat(sql.required("SELECT refunded_quantity,refunded_amount FROM order_item WHERE id=?",item)).containsEntry("refunded_quantity",1L).containsEntry("refunded_amount",new BigDecimal("19.90"));
        JsonNode second=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","剩余一册退款"),a,key()),201);
        JsonNode secondApproved=data(call("POST","/support/after-sales/"+second.path("id").asText()+"/decision",Map.of("approved",true,"note","独立客服复核"),support,null),200);
        String secondRefund=secondApproved.path("refund").path("id").asText();data(call("POST","/finance/refunds/"+secondRefund+"/execute",null,finance,key()),202);
        assertThat(sql.required("SELECT status,financial_status FROM trade_order WHERE id=?",orderId)).containsEntry("status","CLOSED_REFUND").containsEntry("financial_status","REFUNDED");
        assertThat(sql.required("SELECT on_hand FROM inventory WHERE sku_id=?",sku1).get("on_hand")).isEqualTo(100L);
        error(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content","全额退款行不可评价"),a,null),409,"STATE_CONFLICT");
    }

    @Test void tc18ReturnInspectionRestocksOnlyResalableGoodsAndReviewCanBeModerated() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);ship(orderId,shop1);receive(orderId);
        JsonNode apply=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","RETURN_REFUND","quantity",1,"reason","虚构退货"),a,key()),201);
        String afterSaleId=apply.path("id").asText();data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","同意退货"),support,null),200);
        data(call("PUT","/after-sales/"+afterSaleId+"/return-shipment",Map.of("carrier","虚构承运商","trackingNo","RETURN-18"),a,null),200);
        JsonNode inspected=data(call("POST","/merchant/shops/"+shop1+"/after-sales/"+afterSaleId+"/inspection",
                Map.of("accepted",true,"restock",true,"note","验收可再售"),warehouse,key()),200);
        assertThat(inspected.path("status").asText()).isEqualTo("REFUNDING");assertThat(inspected.path("restockQuantity").asInt()).isEqualTo(1);
        String refundId=inspected.path("refund").path("id").asText();data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","RETURN_RESTOCK:"+afterSaleId)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refundId)).isZero();
        assertThat(sql.count("SELECT shipped_quantity FROM shipment_item WHERE order_item_id=?",item)).isEqualTo(2);
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",orderId).get("status")).isEqualTo("COMPLETED");
        Long productId=((Number)sql.required("SELECT product_id FROM order_item WHERE id=?",item).get("product_id")).longValue();
        error(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content","<script>bad</script>"),a,null),400,"PARAM_INVALID");
        JsonNode review=data(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content","虚构评价"),a,null),201);
        error(call("POST","/reviews",Map.of("orderItemId",item,"rating",5,"content","重复评价"),a,null),409,"STATE_CONFLICT");
        assertThat(data(call("GET","/products/"+productId+"/reviews",null,null,null),200).path("total").asInt()).isEqualTo(1);
        data(call("PUT","/ops/reviews/"+review.path("id").asText()+"/visibility",Map.of("status","HIDDEN","reason","虚构违规说明"),operator,null),200);
        assertThat(data(call("GET","/products/"+productId+"/reviews",null,null,null),200).path("total").asInt()).isZero();
        assertThat(data(call("GET","/ops/reviews?status=HIDDEN",null,operator,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/ops/reviews?productId="+productId+"&status=HIDDEN",null,operator,null),200).path("total").asInt()).isEqualTo(1);
        data(call("PUT","/ops/reviews/"+review.path("id").asText()+"/visibility",Map.of("status","HIDDEN","reason","虚构违规说明"),operator,null),200);
        cart(a,sku1,1);cart(a,sku2,1);String expiredOrder=firstOrder(batch());pay(expiredOrder);ship(expiredOrder,shop1);receive(expiredOrder);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 31 DAY) WHERE id=?",expiredOrder);
        error(call("POST","/reviews",Map.of("orderItemId",orderItem(expiredOrder),"rating",4,"content","过期评价"),a,null),409,"STATE_CONFLICT");
    }

    @Test void tc19ReturnWithoutTrackingExpiresButShippedReturnRemainsPending() {
        JsonNode batch=batch();String one=batch.path("orders").get(0).path("id").asText();String two=batch.path("orders").get(1).path("id").asText();
        pay(one);pay(two);ship(one,shop1);ship(two,shop2);receive(one);receive(two);
        String firstItem=orderItem(one),secondItem=orderItem(two);
        String first=data(call("POST","/after-sales",Map.of("orderItemId",firstItem,"type","RETURN_REFUND","quantity",1,"reason","虚构待寄件"),a,key()),201).path("id").asText();
        String second=data(call("POST","/after-sales",Map.of("orderItemId",secondItem,"type","RETURN_REFUND","quantity",1,"reason","虚构已寄件"),a,key()),201).path("id").asText();
        data(call("POST","/support/after-sales/"+first+"/decision",Map.of("approved",true,"note","批准"),support,null),200);
        data(call("POST","/support/after-sales/"+second+"/decision",Map.of("approved",true,"note","批准"),support,null),200);
        var returnBody=Map.of("carrier","虚构承运商","trackingNo","RETURN-PENDING");data(call("PUT","/after-sales/"+first+"/return-shipment",returnBody,a,null),200);
        assertThat(data(call("PUT","/after-sales/"+first+"/return-shipment",returnBody,a,null),200).path("status").asText()).isEqualTo("RETURNING");
        sql.update("UPDATE after_sale SET return_deadline=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id IN (?,?)",first,second);
        sql.update("UPDATE job_lease SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE job_name='after-sale-return-expiry'");
        assertThat(afterSales.expireReturns("stage-f-test-expiry")).isEqualTo(1);
        assertThat(data(call("GET","/after-sales/"+first,null,a,null),200).path("status").asText()).isEqualTo("RETURNING");
        assertThat(data(call("GET","/after-sales/"+second,null,a,null),200).path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test void tc21Tc22Tc23ReconciliationEvidenceSettlementSnapshotsAndNegativeCarryForward() {
        String orderId=firstOrder(batch());pay(orderId);ship(orderId,shop1);receive(orderId);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) WHERE id=?",orderId);
        LocalDate billDate=LocalDate.now().minusDays(9),periodOne=LocalDate.now().minusDays(2),periodTwo=LocalDate.now().minusDays(1);
        java.sql.Timestamp paidAt=java.sql.Timestamp.from(billDate.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(1).toInstant());
        sql.update("UPDATE payment SET paid_at=? WHERE order_id=?",paidAt,orderId);
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",billDate.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);
        assertThat(data(call("POST","/finance/reconciliations",Map.of("billDate",billDate.toString(),"provider","LOCAL_MOCK"),finance,key()),202).path("id").asText()).isEqualTo(run.path("id").asText());
        String runId=run.path("id").asText();
        String issue=sql.insert("INSERT INTO reconciliation_issue(run_id,order_id,issue_key,issue_type,expected_amount,actual_amount) VALUES(?,?,?,'LOCAL_MOCK_REVIEW',39.80,39.80)",runId,orderId,"stage-f-open:"+orderId);
        error(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",periodOne.toString()),finance,key()),409,"SETTLEMENT_BLOCKED");
        data(call("POST","/finance/reconciliation-issues/"+issue+"/resolve",Map.of("resolution","已核对本地模拟凭证","evidenceReference","LOCAL_MOCK:test"),finance,null),200);
        String createKey=key();JsonNode statement=data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",periodOne.toString()),finance,createKey),201);
        assertThat(statement.path("grossAmount").asText()).isEqualTo("39.80");assertThat(statement.path("feeAmount").asText()).isEqualTo("1.19");assertThat(statement.path("payableAmount").asText()).isEqualTo("38.61");
        assertThat(data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",periodOne.toString()),finance,createKey),201)).isEqualTo(statement);
        String settlementId=statement.path("id").asText();long version=statement.path("version").asLong();
        assertThat(data(call("GET","/finance/settlements?shopId="+shop1+"&status=DRAFT&periodDate="+periodOne,null,finance,null),200).path("total").asInt()).isEqualTo(1);
        error(call("POST","/finance/settlements/"+settlementId+"/decision",Map.of("approved",true,"note","同人审批应拒绝","version",version),finance,key()),403,"ACCESS_DENIED");
        JsonNode approved=data(call("POST","/finance/settlements/"+settlementId+"/decision",Map.of("approved",true,"note","复核通过","version",version),financeApprover,key()),200);
        JsonNode paid=data(call("POST","/finance/settlements/"+settlementId+"/mock-payout",Map.of("version",approved.path("version").asLong()),financeApprover,key()),200);
        String voucher=paid.path("mockPayoutNo").asText();assertThat(voucher).startsWith("MOCK-");
        assertThat(data(call("POST","/finance/settlements/"+settlementId+"/mock-payout",Map.of("version",paid.path("version").asLong()),financeApprover,key()),200).path("mockPayoutNo").asText()).isEqualTo(voucher);
        JsonNode merchantSettlements=data(call("GET","/merchant/shops/"+shop1+"/settlements?status=PAID",null,merchant,null),200);
        assertThat(merchantSettlements.path("total").asInt()).isEqualTo(1);assertThat(merchantSettlements.path("items").get(0).hasNonNull("createdBy")).isFalse();
        assertThat(data(call("GET","/merchant/shops/"+shop1+"/settlements/"+settlementId,null,merchant,null),200).hasNonNull("approvedBy")).isFalse();
        error(call("PUT","/ops/reviews/999/visibility",Map.of("status","HIDDEN","reason","不存在"),operator,null),404,"RESOURCE_NOT_FOUND");
        assertThatThrownBy(()->sql.update("UPDATE settlement SET payable_amount=0 WHERE id=?",settlementId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(data(call("GET","/finance/settlements/"+settlementId,null,finance,null),200).path("mockPayoutNo").asText()).isEqualTo(voucher);

        // Test fixture models a later independently verified refund fact after payout; only the new adjustment may change.
        String item=orderItem(orderId);sql.update("UPDATE order_item SET refunded_quantity=1,refunded_amount=19.90 WHERE id=?",item);
        sql.update("UPDATE trade_order SET refunded_amount=19.90,financial_status='PARTIALLY_REFUNDED' WHERE id=?",orderId);
        String lateRun=sql.insert("INSERT INTO reconciliation_run(provider,bill_date,status,completed_at) VALUES('LOCAL_MOCK',?,'SUCCEEDED',UTC_TIMESTAMP(3))",billDate.minusDays(1));
        String lateIssue=sql.insert("INSERT INTO reconciliation_issue(run_id,order_id,issue_key,issue_type,expected_amount,actual_amount) VALUES(?,?,?,'LATE_REFUND_REVIEW',38.61,19.30)",lateRun,orderId,"stage-f-late-refund:"+orderId);
        data(call("POST","/finance/reconciliation-issues/"+lateIssue+"/resolve",Map.of("resolution","已核实后续成功退款","evidenceReference","LOCAL_MOCK:refund"),finance,null),200);
        error(call("POST","/finance/settlement-adjustments",Map.of("originalSettlementId",settlementId,"orderId",orderId,"issueId",lateIssue,"amount","-19.30","reason","不符计算的调整"),finance,null),409,"SETTLEMENT_BLOCKED");
        JsonNode adjustment=data(call("POST","/finance/settlement-adjustments",Map.of("originalSettlementId",settlementId,"orderId",orderId,"issueId",lateIssue,"amount","-19.31","reason","已核实退款调整"),finance,null),201);
        assertThat(adjustment.path("amount").asText()).isEqualTo("-19.31");
        assertThat(data(call("GET","/finance/settlement-adjustments?shopId="+shop1+"&status=PENDING",null,finance,null),200).path("total").asInt()).isEqualTo(1);
        error(call("POST","/finance/settlement-adjustments/"+adjustment.path("id").asText()+"/approve",Map.of("note","创建人不能审批"),finance,null),403,"ACCESS_DENIED");
        data(call("POST","/finance/settlement-adjustments/"+adjustment.path("id").asText()+"/approve",Map.of("note","独立复核"),financeApprover,null),200);
        data(call("POST","/finance/settlement-adjustments/"+adjustment.path("id").asText()+"/approve",Map.of("note","重复审核幂等"),financeApprover,null),200);
        assertThat(data(call("GET","/finance/settlement-adjustments?shopId="+shop1+"&status=APPROVED",null,finance,null),200).path("total").asInt()).isEqualTo(1);
        JsonNode carry=data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",periodTwo.toString()),finance,key()),201);
        assertThat(carry.path("adjustmentAmount").asText()).isEqualTo("-19.31");assertThat(carry.path("payableAmount").asText()).isEqualTo("-19.31");
        error(call("POST","/finance/settlements/"+carry.path("id").asText()+"/decision",Map.of("approved",true,"note","负余额不得出款","version",carry.path("version").asLong()),financeApprover,key()),409,"SETTLEMENT_BLOCKED");
        error(call("POST","/finance/settlements/"+carry.path("id").asText()+"/mock-payout",Map.of("version",carry.path("version").asLong()),financeApprover,key()),409,"SETTLEMENT_BLOCKED");
        assertThat(data(call("GET","/merchant/shops/"+shop1+"/settlements/"+carry.path("id").asText(),null,merchant,null),200).path("adjustments").size()).isEqualTo(1);
        assertThat(data(call("GET","/finance/settlements/"+settlementId,null,finance,null),200).path("payableAmount").asText()).isEqualTo("38.61");
    }

    @Test void activeReturnApplicationExcludesOrderFromSettlementCandidates() {
        String orderId=firstOrder(batch());pay(orderId);ship(orderId,shop1);receive(orderId);
        String item=orderItem(orderId);data(call("POST","/after-sales",Map.of("orderItemId",item,"type","RETURN_REFUND","quantity",1,"reason","待处理申请"),a,key()),201);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) WHERE id=?",orderId);
        error(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",LocalDate.now().minusDays(1).toString()),finance,key()),409,"SETTLEMENT_BLOCKED");
        error(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",LocalDate.now().toString()),finance,key()),409,"SETTLEMENT_BLOCKED");
    }

    @Test void reconciliationPersistsOpenRefundDifferenceAndResolvesWithEvidence() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        JsonNode apply=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","对账待查退款"),a,key()),201);
        String afterSaleId=apply.path("id").asText();JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准待查退款"),support,null),200);
        String refundId=approved.path("refund").path("id").asText();LocalDate date=LocalDate.now().minusDays(3);
        java.sql.Timestamp created=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());
        sql.update("UPDATE refund SET status='PROCESSING',created_at=? WHERE id=?",created,refundId);
        String idem=key();JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,idem),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);assertThat(run.path("differenceCount").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/finance/reconciliations?billDate="+date,null,finance,null),200).path("total").asInt()).isEqualTo(1);
        JsonNode issues=data(call("GET","/finance/reconciliation-issues?status=OPEN",null,finance,null),200);assertThat(issues.path("total").asInt()).isEqualTo(1);
        String issueId=issues.path("items").get(0).path("id").asText();data(call("POST","/finance/reconciliation-issues/"+issueId+"/resolve",Map.of("resolution","渠道仍显示未找到，保留失败证据","evidenceReference","LOCAL_MOCK:NOT_FOUND"),finance,null),200);
        data(call("POST","/finance/reconciliation-issues/"+issueId+"/resolve",Map.of("resolution","渠道仍显示未找到，保留失败证据","evidenceReference","LOCAL_MOCK:NOT_FOUND"),finance,null),200);
        assertThat(data(call("GET","/finance/reconciliation-issues?status=RESOLVED",null,finance,null),200).path("total").asInt()).isEqualTo(1);
        JsonNode noSandboxEvidence=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","ALIPAY_SANDBOX"),finance,key()),202);
        assertThat(noSandboxEvidence.path("status").asText()).isEqualTo("FAILED");
    }

    @Test void reconciliationConfirmsLateCompensationFromChannelEvidence() {
        String orderId=firstOrder(batch());pay(orderId);LocalDate date=LocalDate.now().minusDays(4);
        java.sql.Timestamp created=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());
        sql.update("UPDATE trade_order SET status='CANCELLED',financial_status='PAID' WHERE id=?",orderId);
        var payment=sql.required("SELECT id,payment_no,amount FROM payment WHERE order_id=?",orderId);String refundNo="RL"+key();
        String refundId=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status,created_at) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,'PROCESSING',?)",refundNo,payment.get("id"),payment.get("amount"),created);
        String channelKey="mock:"+payment.get("payment_no")+":refund:"+refundNo;
        sql.insert("INSERT INTO payment_event(payment_id,provider,event_key,event_type,verified,payload_digest,trace_id) VALUES(?,'LOCAL_MOCK',?,'MOCK_CHANNEL',1,'fixture-digest','stage-f-test')",payment.get("id"),channelKey);
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refundId).get("status")).isEqualTo("SUCCEEDED");
        assertThat(sql.required("SELECT status,financial_status FROM trade_order WHERE id=?",orderId)).containsEntry("status","CANCELLED").containsEntry("financial_status","REFUNDED");
    }

    @Test void reconciliationFlagsPaymentTradeNumberMismatch() {
        String orderId=firstOrder(batch());pay(orderId);LocalDate date=LocalDate.now().minusDays(5);
        java.sql.Timestamp paidAt=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());sql.update("UPDATE payment SET paid_at=? WHERE order_id=?",paidAt,orderId);
        var payment=sql.required("SELECT payment_no,amount FROM payment WHERE order_id=?",orderId);
        doReturn(new Fact(FactStatus.PAID,payment.get("payment_no").toString(),"WRONG-TRADE-NO",(BigDecimal)payment.get("amount"),java.time.Instant.now())).when(channel).query(any());
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);assertThat(run.path("differenceCount").asInt()).isGreaterThan(0);
        assertThat(data(call("GET","/finance/reconciliation-issues?status=OPEN",null,finance,null),200).path("total").asInt()).isGreaterThan(0);
    }

    @Test void reconciliationKeepsPaymentUnknownAsOpenDifference() {
        String orderId=firstOrder(batch());pay(orderId);LocalDate date=LocalDate.now().minusDays(8);
        java.sql.Timestamp paidAt=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());sql.update("UPDATE payment SET paid_at=? WHERE order_id=?",paidAt,orderId);
        var payment=sql.required("SELECT id,payment_no,amount FROM payment WHERE order_id=?",orderId);
        doReturn(Fact.unknown(payment.get("payment_no").toString())).when(channel).query(any());
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);assertThat(run.path("differenceCount").asInt()).isGreaterThan(0);
        assertThat(sql.required("SELECT status FROM payment WHERE id=?",payment.get("id")).get("status")).isEqualTo("SUCCEEDED");
        assertThat(data(call("GET","/finance/reconciliation-issues?status=OPEN",null,finance,null),200).path("items").findValuesAsText("issueType")).contains("PAYMENT_QUERY_UNKNOWN");
    }

    @Test void reconciliationKeepsLateCompensationUnknownAsOpenDifference() {
        String orderId=firstOrder(batch());pay(orderId);LocalDate date=LocalDate.now().minusDays(9);
        java.sql.Timestamp created=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());
        sql.update("UPDATE trade_order SET status='CANCELLED',financial_status='PAID' WHERE id=?",orderId);
        var payment=sql.required("SELECT id,payment_no,amount FROM payment WHERE order_id=?",orderId);String refundNo="RL"+key();
        String refundId=sql.insert("INSERT INTO refund(refund_no,payment_id,reason_type,amount,status,created_at) VALUES(?,?,'LATE_PAYMENT_COMPENSATION',?,'PROCESSING',?)",refundNo,payment.get("id"),payment.get("amount"),created);
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);assertThat(run.path("differenceCount").asInt()).isEqualTo(1);
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refundId).get("status")).isEqualTo("PROCESSING");
        assertThat(sql.required("SELECT financial_status FROM trade_order WHERE id=?",orderId).get("financial_status")).isEqualTo("PAID");
        assertThat(data(call("GET","/finance/reconciliation-issues?status=OPEN",null,finance,null),200).path("items").get(0).path("issueType").asText()).isEqualTo("LATE_REFUND_STATUS_MISMATCH");
    }

    @Test void reconciliationAppliesVerifiedChannelRefundFailure() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","渠道失败复核"),a,key()),201).path("id").asText();
        JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准渠道失败复核"),support,null),200);String refundId=approved.path("refund").path("id").asText();
        var refund=sql.required("SELECT r.refund_no,p.payment_no,r.amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refundId);LocalDate date=LocalDate.now().minusDays(6);
        java.sql.Timestamp created=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());sql.update("UPDATE refund SET status='PROCESSING',created_at=? WHERE id=?",created,refundId);
        String refundNo=refund.get("refund_no").toString();BigDecimal amount=(BigDecimal)refund.get("amount");
        doReturn(new RefundFact(RefundStatus.FAILED,refundNo,refund.get("payment_no").toString(),amount,"MOCK-FAILED")).when(channel).queryRefund(any(),eq(refundNo),eq(amount));
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("differenceCount").asInt()).isZero();
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refundId).get("status")).isEqualTo("FAILED");
    }

    @Test void reconciliationAcceptsPreviouslyAppliedNormalRefund() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","核对成功退款"),a,key()),201).path("id").asText();
        JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准成功退款"),support,null),200);String refundId=approved.path("refund").path("id").asText();
        data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);LocalDate date=LocalDate.now().minusDays(7);
        java.sql.Timestamp succeeded=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());sql.update("UPDATE refund SET succeeded_at=? WHERE id=?",succeeded,refundId);
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);assertThat(run.path("differenceCount").asInt()).isZero();
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refundId).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test void acceptedReturnWithoutResaleDoesNotRestock() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);ship(orderId,shop1);receive(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","RETURN_REFUND","quantity",1,"reason","破损退货"),a,key()),201).path("id").asText();
        data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准验收"),support,null),200);
        data(call("PUT","/after-sales/"+afterSaleId+"/return-shipment",Map.of("carrier","虚构承运商","trackingNo","RETURN-NONRESALE"),a,null),200);
        error(call("POST","/merchant/shops/"+shop1+"/after-sales/"+afterSaleId+"/inspection",Map.of("accepted",false,"restock",true,"note","拒绝时不能回补"),warehouse,key()),409,"STATE_CONFLICT");
        JsonNode inspected=data(call("POST","/merchant/shops/"+shop1+"/after-sales/"+afterSaleId+"/inspection",Map.of("accepted",true,"restock",false,"note","不可再售"),warehouse,key()),200);
        assertThat(inspected.path("restockQuantity").asInt()).isZero();String refundId=inspected.path("refund").path("id").asText();data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","RETURN_RESTOCK:"+afterSaleId)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refundId)).isZero();
    }

    @Test void unknownRefundResultKeepsProcessingAndInventoryHeld() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","渠道结果未知"),a,key()),201).path("id").asText();
        JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准未知结果售后"),support,null),200);String refundId=approved.path("refund").path("id").asText();
        var refund=sql.required("SELECT r.refund_no,p.payment_no,r.amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refundId);String no=refund.get("refund_no").toString(),paymentNo=refund.get("payment_no").toString();BigDecimal amount=(BigDecimal)refund.get("amount");
        doReturn(new RefundFact(RefundStatus.UNKNOWN,no,paymentNo,amount,null)).when(channel).refund(any(),eq(no),eq(amount));
        JsonNode processing=data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202);assertThat(processing.path("status").asText()).isEqualTo("PROCESSING");
        error(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),409,"REQUEST_IN_PROGRESS");
        assertThat(sql.required("SELECT on_hand FROM inventory WHERE sku_id=?",sku1).get("on_hand")).isEqualTo(98L);
        assertThat(sql.required("SELECT status FROM after_sale WHERE id=?",afterSaleId).get("status")).isEqualTo("REFUNDING");
    }

    @Test void reconciliationRecoversTimedOutRefundUsingSameRefundNumber() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","恢复超时退款"),a,key()),201).path("id").asText();
        JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准恢复测试"),support,null),200);String refundId=approved.path("refund").path("id").asText();
        var refund=sql.required("SELECT r.refund_no,p.payment_no,r.amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refundId);
        String refundNo=refund.get("refund_no").toString(),paymentNo=refund.get("payment_no").toString();BigDecimal amount=(BigDecimal)refund.get("amount");
        doReturn(new RefundFact(RefundStatus.UNKNOWN,refundNo,paymentNo,amount,null)).when(channel).refund(any(),eq(refundNo),eq(amount));
        assertThat(data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202).path("status").asText()).isEqualTo("PROCESSING");
        LocalDate date=LocalDate.now().minusDays(10);java.sql.Timestamp created=java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2).toInstant());
        sql.update("UPDATE refund SET created_at=? WHERE id=?",created,refundId);
        doReturn(new RefundFact(RefundStatus.SUCCEEDED,refundNo,paymentNo,amount,"MOCK-RECOVERED-REFUND")).when(channel).queryRefund(any(),eq(refundNo),eq(amount));
        JsonNode run=data(call("POST","/finance/reconciliations",Map.of("billDate",date.toString(),"provider","LOCAL_MOCK"),finance,key()),202);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(run.path("checkedCount").asInt()).isEqualTo(1);
        assertThat(sql.required("SELECT refund_no,status FROM refund WHERE id=?",refundId)).containsEntry("refund_no",refundNo).containsEntry("status","SUCCEEDED");
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","REFUND_RESTOCK:"+refundId)).isEqualTo(1);
        assertThat(sql.required("SELECT refunded_quantity,refunded_amount FROM order_item WHERE id=?",item)).containsEntry("refunded_quantity",1L).containsEntry("refunded_amount",new BigDecimal("19.90"));
    }

    @Test void returnEligibilityDeadlineAndDamagedInspectionAreEnforced() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);ship(orderId,shop1);receive(orderId);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 8 DAY) WHERE id=?",orderId);
        error(call("POST","/after-sales",Map.of("orderItemId",item,"type","RETURN_REFUND","quantity",1,"reason","超过售后窗口"),a,key()),409,"STATE_CONFLICT");
        sql.update("UPDATE trade_order SET completed_at=UTC_TIMESTAMP(3) WHERE id=?",orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","RETURN_REFUND","quantity",1,"reason","破损不可再售"),a,key()),201).path("id").asText();
        data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准退回"),support,null),200);
        sql.update("UPDATE after_sale SET return_deadline=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",afterSaleId);
        var returnBody=Map.of("carrier","虚构承运商","trackingNo","RETURN-DAMAGED");error(call("PUT","/after-sales/"+afterSaleId+"/return-shipment",returnBody,a,null),409,"STATE_CONFLICT");
        sql.update("UPDATE after_sale SET return_deadline=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) WHERE id=?",afterSaleId);data(call("PUT","/after-sales/"+afterSaleId+"/return-shipment",returnBody,a,null),200);
        JsonNode rejected=data(call("POST","/merchant/shops/"+shop1+"/after-sales/"+afterSaleId+"/inspection",Map.of("accepted",false,"restock",false,"note","验收不合格"),warehouse,key()),200);
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");assertThat(rejected.hasNonNull("refund")).isFalse();
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?","RETURN_RESTOCK:"+afterSaleId)).isZero();
    }

    @Test void mismatchedSuccessfulRefundEvidenceIsNotApplied() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        String afterSaleId=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","退款金额待核对"),a,key()),201).path("id").asText();
        JsonNode approved=data(call("POST","/support/after-sales/"+afterSaleId+"/decision",Map.of("approved",true,"note","批准待核对"),support,null),200);String refundId=approved.path("refund").path("id").asText();
        var row=sql.required("SELECT r.refund_no,p.payment_no,r.amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refundId);String no=row.get("refund_no").toString();BigDecimal amount=(BigDecimal)row.get("amount");
        doReturn(new RefundFact(RefundStatus.SUCCEEDED,no,row.get("payment_no").toString(),amount.add(new BigDecimal("0.01")),"MOCK-WRONG-AMOUNT")).when(channel).refund(any(),eq(no),eq(amount));
        assertThat(data(call("POST","/finance/refunds/"+refundId+"/execute",null,finance,key()),202).path("status").asText()).isEqualTo("PROCESSING");
        assertThat(sql.required("SELECT status FROM refund WHERE id=?",refundId).get("status")).isEqualTo("PROCESSING");
        assertThat(sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE refund_id=? AND status='OPEN'",refundId)).isEqualTo(1);
    }

    @Test void rejectedSettlementCanBeRebuiltAndChangedSnapshotsCannotBeApproved() {
        String orderId=firstOrder(batch());pay(orderId);ship(orderId,shop1);receive(orderId);
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) WHERE id=?",orderId);
        LocalDate period=LocalDate.now().minusDays(1);JsonNode draft=data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",period.toString()),finance,key()),201);
        JsonNode rejected=data(call("POST","/finance/settlements/"+draft.path("id").asText()+"/decision",Map.of("approved",false,"note","退回重编","version",draft.path("version").asLong()),financeApprover,key()),200);
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        JsonNode rebuilt=data(call("POST","/finance/settlements",Map.of("shopId",shop1,"periodDate",period.toString()),finance,key()),201);
        assertThat(rebuilt.path("id").asText()).isEqualTo(draft.path("id").asText());assertThat(rebuilt.path("status").asText()).isEqualTo("DRAFT");
        String item=orderItem(orderId);sql.update("UPDATE order_item SET refunded_quantity=1,refunded_amount=19.90 WHERE id=?",item);sql.update("UPDATE trade_order SET refunded_amount=19.90,financial_status='PARTIALLY_REFUNDED' WHERE id=?",orderId);
        error(call("POST","/finance/settlements/"+rebuilt.path("id").asText()+"/decision",Map.of("approved",true,"note","快照已变化","version",rebuilt.path("version").asLong()),financeApprover,key()),409,"SETTLEMENT_BLOCKED");
    }

    @Test void requestedAfterSaleCanBeCancelledAndSupportRejectionIsReplaySafe() {
        String orderId=firstOrder(batch());pay(orderId);String item=orderItem(orderId);
        JsonNode first=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","用户撤销申请"),a,key()),201);String firstId=first.path("id").asText();
        assertThat(data(call("POST","/after-sales/"+firstId+"/cancel",null,a,null),200).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data(call("POST","/after-sales/"+firstId+"/cancel",null,a,null),200).path("status").asText()).isEqualTo("CANCELLED");
        JsonNode second=data(call("POST","/after-sales",Map.of("orderItemId",item,"type","REFUND_ONLY","quantity",1,"reason","再次申请"),a,key()),201);String secondId=second.path("id").asText();
        assertThat(data(call("POST","/support/after-sales/"+secondId+"/decision",Map.of("approved",false,"note","证据不足"),support,null),200).path("status").asText()).isEqualTo("REJECTED");
        assertThat(data(call("POST","/support/after-sales/"+secondId+"/decision",Map.of("approved",false,"note","证据不足"),support,null),200).path("status").asText()).isEqualTo("REJECTED");
        error(call("GET","/after-sales?status=INVALID",null,a,null),400,"PARAM_INVALID");
    }

    private String firstOrder(JsonNode batch) {return batch.path("orders").get(0).path("id").asText();}
    private String orderItem(String orderId) {return sql.required("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1",orderId).get("id").toString();}
    private void pay(String orderId) {
        String payment=data(call("POST","/orders/"+orderId+"/payments",null,a,key()),201).path("id").asText();
        data(call("POST","/payments/"+payment+"/mock-confirm",null,a,key()),200);
    }
    private void ship(String orderId,String shop) {
        JsonNode order=data(call("GET","/merchant/shops/"+shop+"/orders/"+orderId,null,merchant,null),200);
        data(call("POST","/merchant/shops/"+shop+"/orders/"+orderId+"/ship",Map.of("carrier","虚构承运商","trackingNo","SHIP-"+orderId,"version",order.path("version").asLong()),merchant,key()),201);
    }
    private void receive(String orderId) {data(call("POST","/orders/"+orderId+"/confirm-receipt",null,a,key()),200);}
}
