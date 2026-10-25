package com.yueji.mall.fulfillment.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.fulfillment.model.FulfillmentModels.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.platform.service.OutboxService;
import com.yueji.mall.trade.model.TradeModels.*;
import com.yueji.mall.trade.repository.TradeRepository;
import com.yueji.mall.trade.service.TradeFulfillmentAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class FulfillmentService {
    private final SqlStore sql;private final DatabaseTransaction tx;private final IdentityRepository identities;private final IdentityPolicy policy;
    private final IdempotencyService idempotency;private final TradeRepository trade;private final TradeFulfillmentAccess tradeAccess;private final AuditRepository audit;private final Clock clock;
    public FulfillmentService(SqlStore sql,DatabaseTransaction tx,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,TradeRepository trade,TradeFulfillmentAccess tradeAccess,AuditRepository audit,Clock clock) {
        this.sql=sql;this.tx=tx;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.trade=trade;this.tradeAccess=tradeAccess;this.audit=audit;this.clock=clock;
    }
    public PageData<OrderSummary> list(Actor actor,String shop,String status,String orderNo,PageQuery page) {
        shopScope(actor,shop,"shop.order.read");if(page.sort()!=null || status!=null && !Set.of("PENDING_PAYMENT","CLOSING","PAID","SHIPPED","COMPLETED","CANCELLED","CLOSED_REFUND").contains(status) || orderNo!=null && (orderNo.isBlank()||orderNo.length()>40)) throw new BusinessException(ErrorCode.PARAM_INVALID);
        String where=" WHERE o.shop_id=?";var args=new ArrayList<Object>();args.add(shop);if(status!=null){where+=" AND o.status=?";args.add(status);}if(orderNo!=null){where+=" AND o.order_no=?";args.add(orderNo);}
        long count=sql.count("SELECT COUNT(*) FROM trade_order o"+where,args.toArray());args.add(page.pageSize());args.add(page.offset());
        var rows=sql.rows("SELECT o.*,s.name shop_name,(SELECT COUNT(*) FROM order_item i WHERE i.order_id=o.id) item_count FROM trade_order o JOIN shop s ON s.id=o.shop_id"+where+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",args.toArray());
        return new PageData<>(rows.stream().map(r->new OrderSummary(new ApiId(text(r,"id")),text(r,"order_no"),new ApiId(shop),text(r,"shop_name"),text(r,"status"),text(r,"financial_status"),TradeRepository.money(r,"payable_amount"),TradeRepository.money(r,"refunded_amount"),RowValues.instant(r.get("expires_at")),RowValues.instant(r.get("created_at")),((Number)r.get("item_count")).intValue())).toList(),page.page(),page.pageSize(),count);
    }
    public OrderView detail(Actor actor,String shop,String id) {shopScope(actor,shop,"shop.order.read");new ApiId(id);return trade.viewInShop(shop,id);}
    public ShipmentView ship(Actor actor,String shop,String orderId,ShipInput input,String key,String trace) {
        new ApiId(orderId);return tx.run(()->{guard(actor,shop,"shop.shipment.write");return idempotency.execute(actor,"API-40",key,List.of(shop,orderId,input.carrier(),input.trackingNo(),input.version()),"shipment",orderId,ShipmentView.class,()->{
            var order=sql.required("SELECT * FROM trade_order WHERE id=? AND shop_id=? FOR UPDATE",orderId,shop);long version=((Number)order.get("version")).longValue();
            if(version!=input.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            if(!"PAID".equals(text(order,"status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var items=sql.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);
            if(items.isEmpty() || sql.count("SELECT COUNT(*) FROM after_sale WHERE order_id=? AND status IN ('REQUESTED','APPROVED','RETURNING','RECEIVED','REFUNDING')",orderId)>0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var quantities=new LinkedHashMap<String,Integer>();for(var item:items){int remaining=((Number)item.get("quantity")).intValue()-((Number)item.get("refunded_quantity")).intValue();if(remaining>0) quantities.put(text(item,"id"),remaining);}
            if(quantities.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var now=clock.instant();String shipment=sql.insert("INSERT INTO shipment(order_id,carrier,tracking_no,shipped_by,shipped_at) VALUES(?,?,?,?,?)",orderId,input.carrier().trim(),input.trackingNo().trim(),actor.userId(),Timestamp.from(now));
            quantities.forEach((item,qty)->sql.insert("INSERT INTO shipment_item(shipment_id,order_item_id,shipped_quantity) VALUES(?,?,?)",shipment,item,qty));
            sql.insert("INSERT INTO shipment_event(shipment_id,event_time,description,source,operator_id) VALUES(?,?,'已交由承运方配送','SYSTEM',?)",shipment,Timestamp.from(now),actor.userId());
            tradeAccess.shipped(orderId,shipment,actor.userId(),input.version(),now,trace);
            audit.appendReason(actor.userId(),"shipment.create","shipment",shipment,shop,"SUCCESS","整体发货");return shipment(shipment);
        });});
    }
    public ShipmentView addEvent(Actor actor,String shop,String shipmentId,EventInput input,String trace) {
        new ApiId(shipmentId);return tx.run(()->{guard(actor,shop,"shop.shipment.write");var pre=sql.required("SELECT order_id FROM shipment WHERE id=?",shipmentId);String orderId=text(pre,"order_id");
            sql.required("SELECT id FROM trade_order WHERE id=? AND shop_id=? FOR UPDATE",orderId,shop);sql.rows("SELECT id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);
            var shipment=sql.required("SELECT * FROM shipment WHERE id=? AND order_id=? FOR UPDATE",shipmentId,orderId);
            if(input.eventTime().isBefore(RowValues.instant(shipment.get("shipped_at")))) throw new BusinessException(ErrorCode.PARAM_INVALID);
            sql.insert("INSERT INTO shipment_event(shipment_id,event_time,description,source,operator_id) VALUES(?,?,?,'MANUAL',?)",shipmentId,Timestamp.from(input.eventTime()),input.description().trim(),actor.userId());
            audit.appendReason(actor.userId(),"shipment.event.add","shipment",shipmentId,shop,"SUCCESS","MANUAL");return shipment(shipmentId);
        });
    }
    public OrderView confirmReceipt(Actor actor,String orderId,String key,String trace) {
        new ApiId(orderId);return tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.order");return idempotency.execute(actor,"API-36",key,List.of(orderId),"order",orderId,OrderView.class,()->{
            var order=sql.required("SELECT * FROM trade_order WHERE id=? AND user_id=? FOR UPDATE",orderId,actor.userId());sql.rows("SELECT id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);
            if("SHIPPED".equals(text(order,"status"))){var ship=sql.required("SELECT * FROM shipment WHERE order_id=? FOR UPDATE",orderId);var now=clock.instant();
                if(sql.update("UPDATE shipment SET received_at=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND received_at IS NULL",Timestamp.from(now),ship.get("id"))==1){
                    tradeAccess.received(orderId,ship.get("id").toString(),actor.userId(),now,trace);audit.append(actor.userId(),"order.confirm_receipt","order",orderId,null,"SUCCESS");
                }
            } else if(!"COMPLETED".equals(text(order,"status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            return trade.view(actor.userId(),orderId);
        });});
    }
    public ShipmentView shipment(String id) {
        var row=sql.required("SELECT * FROM shipment WHERE id=?",id);var items=sql.rows("SELECT * FROM shipment_item WHERE shipment_id=? ORDER BY id",id).stream().map(r->new ItemView(new ApiId(text(r,"order_item_id")),((Number)r.get("shipped_quantity")).intValue())).toList();
        var events=sql.rows("SELECT * FROM shipment_event WHERE shipment_id=? ORDER BY event_time,id",id).stream().map(r->new EventView(RowValues.instant(r.get("event_time")),text(r,"description"),text(r,"source"))).toList();
        return new ShipmentView(new ApiId(id),text(row,"carrier"),text(row,"tracking_no"),RowValues.instant(row.get("shipped_at")),RowValues.instant(row.get("received_at")),items,events);
    }
    private void shopScope(Actor actor,String shop,String permission){new ApiId(shop);policy.require(actor,permission);if(!identities.hasShopMembership(actor.userId(),shop,"MERCHANT")&&!identities.hasShopMembership(actor.userId(),shop,"WAREHOUSE"))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    private void guard(Actor actor,String shop,String permission){identities.shareCommerceAnchor();identities.lockUser(actor.userId());shopScope(actor,shop,permission);}
}
