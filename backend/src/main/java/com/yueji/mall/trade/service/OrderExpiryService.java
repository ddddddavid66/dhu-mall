package com.yueji.mall.trade.service;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.common.service.DatabaseTransaction;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderExpiryService {
    private final SqlStore sql;private final DatabaseTransaction transactions;private final OrderService orders;
    public OrderExpiryService(SqlStore sql,DatabaseTransaction transactions,OrderService orders) {this.sql=sql;this.transactions=transactions;this.orders=orders;}
    /** 数据库租约防重入，事务内锁订单；不扫描预占并直接释放，也不调用渠道。 */
    public int scan(String owner) {
        return transactions.run(()-> {
            sql.update("INSERT IGNORE INTO job_lease(job_name,owner,lease_until) VALUES('order-expiry',?,UTC_TIMESTAMP(3))",owner);
            var lease=sql.required("SELECT * FROM job_lease WHERE job_name='order-expiry' FOR UPDATE");
            if(sql.count("SELECT COUNT(*) FROM job_lease WHERE job_name='order-expiry' AND lease_until>UTC_TIMESTAMP(3) AND owner<>?",owner)>0) return 0;
            sql.update("UPDATE job_lease SET owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",owner,lease.get("id"));
            var due=sql.rows("SELECT id FROM trade_order WHERE status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED");
            for(var row:due) orders.closing(row.get("id").toString(),null,"支付超时待核实关单","EXPIRE_REQUEST","expiry-"+UUID.randomUUID());
            return due.size();
        });
    }
}
