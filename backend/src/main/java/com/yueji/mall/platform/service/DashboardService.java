package com.yueji.mall.platform.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.DashboardQueries;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final DashboardQueries queries;
    private final IdentityPolicy policy;
    private final IdentityRepository identities;
    private final Clock clock;
    public DashboardService(DashboardQueries queries, IdentityPolicy policy, IdentityRepository identities, Clock clock) {
        this.queries = queries; this.policy = policy; this.identities = identities; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Map<String,Object> view(Actor actor, String shopId) {
        policy.require(actor, "dashboard.read");
        if (shopId != null) new ApiId(shopId);
        // 重新读取有效权限，不能相信客户端角色或旧 JWT 里的范围。
        var permissions = identities.permissions(actor.userId());
        boolean orders = permissions.contains("shop.order.read");
        boolean support = permissions.contains("support.after_sale.read");
        boolean merchantSupport = permissions.contains("shop.after_sale.read");
        if (shopId != null && (orders || merchantSupport)
                && !identities.hasShopMembership(actor.userId(), shopId, "MERCHANT")
                && !identities.hasShopMembership(actor.userId(), shopId, "WAREHOUSE"))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var day = clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var from = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var to = day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var result = new LinkedHashMap<String,Object>();
        if (orders) {
            result.put("todayOrderCount", queries.orders(actor.userId(), shopId, from, to, false));
            result.put("pendingShipmentCount", queries.orders(actor.userId(), shopId, from, to, true));
        }
        if (support || merchantSupport) result.put("openAfterSaleCount", queries.afterSales(support ? null : actor.userId(), shopId));
        if (permissions.contains("finance.reconcile")) {
            result.put("todayPaidAmount", new ApiMoney(queries.paid(shopId, from, to)));
            result.put("unresolvedIssueCount", queries.issues(shopId));
        }
        // 纯管理员/运营没有业务指标权限，返回空对象而非虚构零值。
        return result;
    }
}
