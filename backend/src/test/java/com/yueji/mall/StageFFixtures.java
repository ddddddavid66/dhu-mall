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

abstract class StageFFixtures extends StageEFixtures {
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

    protected String firstOrder(JsonNode batch) {return batch.path("orders").get(0).path("id").asText();}
    protected String orderItem(String orderId) {return sql.required("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1",orderId).get("id").toString();}
    protected void pay(String orderId) {
        String payment=data(call("POST","/orders/"+orderId+"/payments",null,a,key()),201).path("id").asText();
        data(call("POST","/payments/"+payment+"/mock-confirm",null,a,key()),200);
    }
    protected void ship(String orderId,String shop) {
        JsonNode order=data(call("GET","/merchant/shops/"+shop+"/orders/"+orderId,null,merchant,null),200);
        data(call("POST","/merchant/shops/"+shop+"/orders/"+orderId+"/ship",Map.of("carrier","虚构承运商","trackingNo","SHIP-"+orderId,"version",order.path("version").asLong()),merchant,key()),201);
    }
    protected void receive(String orderId) {data(call("POST","/orders/"+orderId+"/confirm-receipt",null,a,key()),200);}
}
