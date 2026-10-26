package com.yueji.mall;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class DashboardHttpTests extends StageEFixtures {
    @Test void dashboardRequiresPermissionAndOmitsUnauthorizedMetrics() {
        error(call("GET","/dashboard",null,null,null),401,"AUTH_REQUIRED");
        error(call("GET","/dashboard",null,a,null),403,"ACCESS_DENIED");
        var admin=buyer("dashboard_admin","ADMIN");
        assertThat(data(call("GET","/dashboard",null,admin,null),200).size()).isZero();
        error(call("GET","/dashboard?shopId=invalid",null,admin,null),400,"PARAM_INVALID");
    }
    @Test void merchantMetricsAreRestrictedToActiveMembershipAndCannotReadOtherShop() {
        var merchant=buyer("dashboard_merchant","MERCHANT");
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'MERCHANT')",shop1,merchant.id());
        batch();
        var view=data(call("GET","/dashboard?shopId="+shop1,null,merchant,null),200);
        assertThat(view.path("todayOrderCount").asLong()).isEqualTo(1);
        assertThat(view.has("todayPaidAmount")).isFalse();
        assertThat(view.has("unresolvedIssueCount")).isFalse();
        assertThat(data(call("GET","/dashboard",null,merchant,null),200).path("todayOrderCount").asLong()).isEqualTo(1);
        error(call("GET","/dashboard?shopId="+shop2,null,merchant,null),404,"RESOURCE_NOT_FOUND");
        sql.update("UPDATE shop_member SET status='DISABLED' WHERE user_id=?",merchant.id());
        error(call("GET","/dashboard?shopId="+shop1,null,merchant,null),404,"RESOURCE_NOT_FOUND");
    }
    @Test void financeAndSupportOnlyReceiveTheirOwnMetrics() {
        var finance=buyer("dashboard_finance","FINANCE");
        var support=buyer("dashboard_support","SUPPORT");
        var batch=batch(); String order=order(batch);
        var payment=data(call("POST","/orders/"+order+"/payments",null,a,key()),201);
        data(call("POST","/payments/"+payment.path("id").asText()+"/mock-confirm",null,a,key()),200);
        var view=data(call("GET","/dashboard",null,finance,null),200);
        assertThat(view.path("todayPaidAmount").asText()).isEqualTo("39.80");
        assertThat(view.has("todayOrderCount")).isFalse();
        assertThat(view.has("pendingShipmentCount")).isFalse();
        assertThat(data(call("GET","/dashboard",null,support,null),200).fieldNames()).toIterable().containsExactly("openAfterSaleCount");
        sql.update("UPDATE payment SET paid_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 DAY)");
        assertThat(data(call("GET","/dashboard",null,finance,null),200).path("todayPaidAmount").asText()).isEqualTo("0.00");
    }
    @Test void warehouseAndSupportScopeAreIndependentlyAuthorized() {
        var warehouse=buyer("dashboard_warehouse","WAREHOUSE");
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'WAREHOUSE')",shop1,warehouse.id());
        batch();
        var view=data(call("GET","/dashboard?shopId="+shop1,null,warehouse,null),200);
        assertThat(view.path("todayOrderCount").asLong()).isEqualTo(1);
        assertThat(view.has("todayPaidAmount")).isFalse();
        var support=buyer("dashboard_support_scoped","SUPPORT");
        assertThat(data(call("GET","/dashboard?shopId="+shop2,null,support,null),200).has("openAfterSaleCount")).isTrue();
        var operator=buyer("dashboard_operator","OPERATOR");
        assertThat(data(call("GET","/dashboard",null,operator,null),200).size()).isZero();
    }
}
