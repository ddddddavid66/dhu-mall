package com.yueji.mall.trade.repository;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.trade.model.TradeModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;
import static com.yueji.mall.catalog.repository.CatalogRepository.number;

@Repository
public class TradeRepository {
    private final SqlStore sql;
    private static final String SUMMARY="SELECT o.*,s.name shop_name,(SELECT COUNT(*) FROM order_item i WHERE i.order_id=o.id) item_count FROM trade_order o JOIN shop s ON s.id=o.shop_id";
    public TradeRepository(SqlStore sql) {this.sql=sql;}
    public Map<String,Object> owned(String user,String id,boolean lock) {
        new ApiId(id);return sql.required("SELECT * FROM trade_order WHERE user_id=? AND id=?"+(lock?" FOR UPDATE":""),user,id);
    }
    public OrderSummary summary(Map<String,Object> r) {
        return new OrderSummary(new ApiId(text(r,"id")),text(r,"order_no"),new ApiId(text(r,"shop_id")),text(r,"shop_name"),text(r,"status"),text(r,"financial_status"),money(r,"payable_amount"),money(r,"refunded_amount"),time(r,"expires_at"),time(r,"created_at"),(int)number(r,"item_count"));
    }
    public BatchView batch(String user,String id) {
        new ApiId(id);var row=sql.required("SELECT * FROM checkout_batch WHERE user_id=? AND id=?",user,id);
        var orders=sql.rows(SUMMARY+" WHERE o.user_id=? AND o.batch_id=? ORDER BY o.shop_id,o.id",user,id).stream().map(this::summary).toList();
        return new BatchView(new ApiId(id),text(row,"batch_no"),money(row,"total_amount"),orders);
    }
    public PageData<OrderSummary> list(String user,OrderStatus status,String orderNo,PageQuery page) {
        if(page.sort()!=null || orderNo!=null && (orderNo.isBlank() || orderNo.length()>40)) throw new BusinessException(ErrorCode.PARAM_INVALID);
        String where=" WHERE o.user_id=?";var args=new ArrayList<Object>();args.add(user);
        if(status!=null) {where+=" AND o.status=?";args.add(status.name());}
        if(orderNo!=null) {where+=" AND o.order_no=?";args.add(orderNo);}
        long total=sql.count("SELECT COUNT(*) FROM trade_order o"+where,args.toArray());
        args.add(page.pageSize());args.add(page.offset());
        var rows=sql.rows(SUMMARY+where+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",args.toArray());
        return new PageData<>(rows.stream().map(this::summary).toList(),page.page(),page.pageSize(),total);
    }
    public OrderView view(String user,String id) {
        new ApiId(id);var row=sql.required(SUMMARY+" WHERE o.user_id=? AND o.id=?",user,id);return detail(row);
    }
    /** 受调用方本店权限校验保护的履约订单查询。 */
    public OrderView viewInShop(String shop,String id) {
        new ApiId(id);var row=sql.required(SUMMARY+" WHERE o.shop_id=? AND o.id=?",shop,id);return detail(row);
    }
    private OrderView detail(Map<String,Object> row) {
        String id=text(row,"id");var s=summary(row);
        var items=sql.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY id",id).stream().map(r->new OrderItemView(new ApiId(text(r,"id")),new ApiId(text(r,"sku_id")),text(r,"book_title"),text(r,"isbn"),money(r,"unit_price"),(int)number(r,"quantity"),money(r,"line_amount"),(int)number(r,"refunded_quantity"),money(r,"refunded_amount"))).toList();
        var logs=sql.rows("SELECT * FROM order_status_log WHERE order_id=? ORDER BY created_at,id",id).stream().map(r->new Timeline(text(r,"to_status"),text(r,"action"),time(r,"created_at"),text(r,"reason"))).toList();
        Object shipment=sql.one("SELECT * FROM shipment WHERE order_id=?",id).map(sh->{
            var result=new LinkedHashMap<String,Object>();result.put("id",new ApiId(text(sh,"id")));result.put("carrier",text(sh,"carrier"));result.put("trackingNo",text(sh,"tracking_no"));result.put("shippedAt",time(sh,"shipped_at"));result.put("receivedAt",time(sh,"received_at"));
            result.put("items",sql.rows("SELECT * FROM shipment_item WHERE shipment_id=? ORDER BY id",sh.get("id")).stream().map(i->Map.of("orderItemId",new ApiId(text(i,"order_item_id")),"shippedQuantity",number(i,"shipped_quantity"))).toList());
            result.put("events",sql.rows("SELECT * FROM shipment_event WHERE shipment_id=? ORDER BY event_time,id",sh.get("id")).stream().map(e->Map.of("eventTime",time(e,"event_time"),"description",text(e,"description"),"source",text(e,"source"))).toList());return result;
        }).orElse(null);
        return new OrderView(s.id(),s.orderNo(),s.shopId(),s.shopName(),s.status(),s.financialStatus(),s.payableAmount(),s.refundedAmount(),s.expiresAt(),s.createdAt(),s.itemCount(),items,
                new Receiver(text(row,"receiver_name"),text(row,"receiver_phone"),text(row,"receiver_address")),time(row,"paid_at"),time(row,"shipped_at"),time(row,"completed_at"),time(row,"closed_at"),shipment,logs,number(row,"version"));
    }
    public void log(String order,String from,String to,String action,String actor,String reason,String trace) {
        sql.insert("INSERT INTO order_status_log(order_id,from_status,to_status,action,operator_id,reason,trace_id) VALUES(?,?,?,?,?,?,?)",order,from,to,action,actor,reason,trace);
    }
    public static ApiMoney money(Map<String,Object> row,String key) {return new ApiMoney((BigDecimal)row.get(key));}
    private static java.time.Instant time(Map<String,Object> row,String key) {return RowValues.instant(row.get(key));}
}
