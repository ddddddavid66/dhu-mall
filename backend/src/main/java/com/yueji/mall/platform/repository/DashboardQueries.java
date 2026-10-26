package com.yueji.mall.platform.repository;

import com.yueji.mall.common.persistence.SqlStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 只读跨领域汇总；不修改交易、售后或财务事实。 */
@Repository
public class DashboardQueries {
    private final SqlStore sql;
    public DashboardQueries(SqlStore sql) { this.sql = sql; }

    public long orders(String userId, String shopId, Instant from, Instant to, boolean pending) {
        var args = new ArrayList<Object>();
        String where = scope("o.shop_id", userId, shopId, args);
        if (pending) where += " AND o.status='PAID'";
        else { where += " AND o.created_at>=? AND o.created_at<?"; args.add(Timestamp.from(from)); args.add(Timestamp.from(to)); }
        return sql.count("SELECT COUNT(*) FROM trade_order o WHERE " + where, args.toArray());
    }
    public long afterSales(String userId, String shopId) {
        var args = new ArrayList<Object>();
        String where = scope("o.shop_id", userId, shopId, args);
        return sql.count("SELECT COUNT(*) FROM after_sale a JOIN trade_order o ON o.id=a.order_id WHERE " + where
                + " AND a.status IN ('REQUESTED','APPROVED','RETURNING','RECEIVED','REFUNDING')", args.toArray());
    }
    public BigDecimal paid(String shopId, Instant from, Instant to) {
        var args = new ArrayList<Object>(List.of(Timestamp.from(from), Timestamp.from(to)));
        String where = scope("o.shop_id", null, shopId, args);
        return (BigDecimal) sql.required("SELECT COALESCE(SUM(p.amount),0.00) amount FROM payment p JOIN trade_order o ON o.id=p.order_id "
                + "WHERE p.status='SUCCEEDED' AND p.paid_at>=? AND p.paid_at<? AND " + where, args.toArray()).get("amount");
    }
    public long issues(String shopId) {
        if (shopId == null) return sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE status='OPEN'");
        return sql.count("SELECT COUNT(*) FROM reconciliation_issue i JOIN trade_order o ON o.id=i.order_id WHERE i.status='OPEN' AND o.shop_id=?", shopId);
    }
    private String scope(String column, String userId, String shopId, List<Object> args) {
        String where = "1=1";
        if (userId != null) {
            where += " AND EXISTS (SELECT 1 FROM shop_member m WHERE m.shop_id=" + column + " AND m.user_id=? AND m.status='ACTIVE')";
            args.add(userId);
        }
        if (shopId != null) { where += " AND " + column + "=?"; args.add(shopId); }
        return where;
    }
}
