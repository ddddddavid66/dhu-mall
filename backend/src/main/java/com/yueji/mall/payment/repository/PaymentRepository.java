package com.yueji.mall.payment.repository;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class PaymentRepository {
    private final SqlStore sql;
    public PaymentRepository(SqlStore sql) {this.sql=sql;}
    public Map<String,Object> row(String id) {new ApiId(id);return sql.required("SELECT * FROM payment WHERE id=?",id);}
    public Map<String,Object> owned(String user,String id) {new ApiId(id);return sql.required("SELECT p.* FROM payment p JOIN trade_order o ON o.id=p.order_id WHERE p.id=? AND o.user_id=?",id,user);}
    public Ticket ticket(Map<String,Object> row) {
        var order=sql.required("SELECT expires_at FROM trade_order WHERE id=?",row.get("order_id"));
        return new Ticket(text(row,"id"),text(row,"order_id"),text(row,"payment_no"),text(row,"provider"),(BigDecimal)row.get("amount"),RowValues.instant(order.get("expires_at")));
    }
    public PaymentView view(String id,PaymentAction action) {
        var r=row(id);return new PaymentView(new ApiId(id),text(r,"payment_no"),new ApiId(text(r,"order_id")),text(r,"provider"),text(r,"status"),new ApiMoney((BigDecimal)r.get("amount")),text(r,"currency"),RowValues.instant(r.get("paid_at")),action);
    }
    public void issue(String key,String type,String order,String payment,BigDecimal expected,BigDecimal actual) {
        sql.update("INSERT IGNORE INTO reconciliation_issue(issue_key,issue_type,order_id,payment_id,expected_amount,actual_amount) VALUES(?,?,?,?,?,?)",key,type,order,payment,expected,actual);
    }
}
