package com.yueji.mall.aftersales.service;

import com.yueji.mall.aftersales.model.AfterSaleModels.*;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.inventory.service.InventoryService;
import com.yueji.mall.inventory.service.ReservationService;
import com.yueji.mall.payment.service.RefundService;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.platform.service.OutboxService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class AfterSaleService {
    private static final Set<String> ACTIVE=Set.of("REQUESTED","APPROVED","RETURNING","RECEIVED","REFUNDING");
    private final SqlStore sql;private final DatabaseTransaction tx;private final IdentityRepository identities;private final IdentityPolicy policy;
    private final IdempotencyService idempotency;private final ReservationService reservations;private final InventoryService inventory;private final RefundService refunds;
    private final AuditRepository audit;private final OutboxService outbox;private final Clock clock;
    public AfterSaleService(SqlStore sql,DatabaseTransaction tx,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,ReservationService reservations,InventoryService inventory,RefundService refunds,AuditRepository audit,OutboxService outbox,Clock clock) {
        this.sql=sql;this.tx=tx;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.reservations=reservations;this.inventory=inventory;this.refunds=refunds;this.audit=audit;this.outbox=outbox;this.clock=clock;
    }
    public AfterSaleView apply(Actor actor,ApplyInput input,String key,String trace) {
        var evidence=input.evidenceFileIds()==null?List.<ApiId>of():input.evidenceFileIds();if(evidence.size()>5||evidence.stream().map(ApiId::value).distinct().count()!=evidence.size())throw new BusinessException(ErrorCode.PARAM_INVALID);
        return tx.run(()->{memberGuard(actor);return idempotency.execute(actor,"API-42",key,List.of(input.orderItemId(),input.type(),input.quantity(),input.reason(),evidence),"after_sale",null,AfterSaleView.class,()->{
            String itemId=input.orderItemId().value();var itemRef=sql.required("SELECT order_id FROM order_item WHERE id=?",itemId);String orderId=text(itemRef,"order_id");
            var order=sql.required("SELECT * FROM trade_order WHERE id=? AND user_id=? FOR UPDATE",orderId,actor.userId());
            var item=sql.required("SELECT * FROM order_item WHERE id=? AND order_id=? FOR UPDATE",itemId,orderId);
            boolean unshipped="PAID".equals(text(order,"status"))&&sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=?",orderId)==0;
            if("REFUND_ONLY".equals(input.type())&&!unshipped)throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if("RETURN_REFUND".equals(input.type())) {
                if(!"COMPLETED".equals(text(order,"status"))||order.get("completed_at")==null||sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=? AND received_at IS NOT NULL",orderId)==0||Duration.between(RowValues.instant(order.get("completed_at")),clock.instant()).compareTo(Duration.ofDays(7))>0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            }
            long requested=((Number)item.get("quantity")).longValue();long refunded=((Number)item.get("refunded_quantity")).longValue();
            long held=sql.rows("SELECT quantity FROM after_sale WHERE order_item_id=? AND status IN ('REQUESTED','APPROVED','RETURNING','RECEIVED','REFUNDING')",itemId).stream().mapToLong(r->((Number)r.get("quantity")).longValue()).sum();
            if(held>0)throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(input.quantity()>requested-refunded-held)throw new BusinessException(ErrorCode.REFUND_LIMIT_EXCEEDED);
            for(ApiId file:evidence){var f=sql.required("SELECT * FROM file_object WHERE id=? AND owner_user_id=? AND purpose='AFTER_SALE_EVIDENCE' AND visibility='PRIVATE' AND status='ACTIVE'",file.value(),actor.userId());if(f.get("shop_id")!=null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
            BigDecimal amount=((BigDecimal)item.get("unit_price")).multiply(BigDecimal.valueOf(input.quantity())).setScale(2);
            String id=sql.insert("INSERT INTO after_sale(after_sale_no,order_id,order_item_id,user_id,type,quantity,requested_amount,reason) VALUES(?,?,?,?,?,?,?,?)","A"+UUID.randomUUID().toString().replace("-",""),orderId,itemId,actor.userId(),input.type(),input.quantity(),amount,input.reason().trim());
            for(ApiId file:evidence)sql.insert("INSERT INTO after_sale_evidence(after_sale_id,file_id) VALUES(?,?)",id,file.value());
            audit.appendReason(actor.userId(),"after_sale.apply","after_sale",id,null,"SUCCESS",input.type());return view(id);
        });});
    }
    public PageData<AfterSaleView> mine(Actor actor,String status,PageQuery page) {policy.require(actor,"member.after_sale");return page("a.user_id=?",List.of(actor.userId()),status,page);}
    public AfterSaleView mineView(Actor actor,String id) {policy.require(actor,"member.after_sale");new ApiId(id);return viewOwned(actor.userId(),id);}
    public AfterSaleView cancel(Actor actor,String id,String trace) {
        new ApiId(id);return tx.run(()->{memberGuard(actor);var ref=sql.required("SELECT order_id FROM after_sale WHERE id=? AND user_id=?",id,actor.userId());var order=sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE",ref.get("order_id"));sql.required("SELECT id FROM order_item WHERE id=(SELECT order_item_id FROM after_sale WHERE id=?) FOR UPDATE",id);var a=sql.required("SELECT * FROM after_sale WHERE id=? AND user_id=? FOR UPDATE",id,actor.userId());
            if("CANCELLED".equals(text(a,"status")))return view(id);if(!"REQUESTED".equals(text(a,"status")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            sql.update("UPDATE after_sale SET status='CANCELLED',updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='REQUESTED'",id);audit.append(actor.userId(),"after_sale.cancel","after_sale",id,null,"SUCCESS");return view(id);});
    }
    public AfterSaleView returnShipment(Actor actor,String id,ReturnInput input) {
        new ApiId(id);return tx.run(()->{memberGuard(actor);var ref=sql.required("SELECT order_id,user_id FROM after_sale WHERE id=?",id);if(!actor.userId().equals(text(ref,"user_id")))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",ref.get("order_id"));sql.required("SELECT id FROM order_item WHERE id=(SELECT order_item_id FROM after_sale WHERE id=?) FOR UPDATE",id);var a=sql.required("SELECT * FROM after_sale WHERE id=? FOR UPDATE",id);
            String status=text(a,"status"),carrier=input.carrier().trim(),tracking=input.trackingNo().trim();
            if("RETURNING".equals(status)&&Objects.equals(a.get("return_carrier"),carrier)&&Objects.equals(a.get("return_tracking_no"),tracking))return view(id);
            if(!"APPROVED".equals(status)||!"RETURN_REFUND".equals(text(a,"type")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var deadline=RowValues.instant(a.get("return_deadline"));if(deadline==null||deadline.isBefore(clock.instant()))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            sql.update("UPDATE after_sale SET status='RETURNING',return_carrier=?,return_tracking_no=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='APPROVED'",carrier,tracking,id);audit.append(actor.userId(),"after_sale.return_shipment","after_sale",id,null,"SUCCESS");return view(id);});
    }
    public PageData<AfterSaleView> supportList(Actor actor,String status,String shop,PageQuery page) {policy.require(actor,"support.after_sale.read");String clause="1=1";var args=new ArrayList<Object>();if(shop!=null){new ApiId(shop);clause+=" AND o.shop_id=?";args.add(shop);}return page(clause,args,status,page);}
    public AfterSaleView supportView(Actor actor,String id) {policy.require(actor,"support.after_sale.read");new ApiId(id);return view(id);}
    public OrderSnapshot supportOrder(Actor actor,String id) {
        policy.require(actor,"support.after_sale.read");new ApiId(id);var ref=sql.required("SELECT order_id FROM after_sale WHERE id=?",id);var order=sql.required("SELECT o.* FROM trade_order o JOIN after_sale a ON a.order_id=o.id WHERE a.id=?",id);
        var lines=sql.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY id",ref.get("order_id")).stream().map(r->new LineSnapshot(new ApiId(text(r,"id")),text(r,"book_title"),text(r,"isbn"),new ApiMoney((BigDecimal)r.get("unit_price")),((Number)r.get("quantity")).intValue(),((Number)r.get("refunded_quantity")).intValue(),new ApiMoney((BigDecimal)r.get("refunded_amount")))).toList();
        return new OrderSnapshot(new ApiId(text(order,"id")),text(order,"order_no"),text(order,"status"),text(order,"financial_status"),new ApiMoney((BigDecimal)order.get("payable_amount")),new ApiMoney((BigDecimal)order.get("refunded_amount")),lines,text(order,"receiver_name"),text(order,"receiver_phone"),text(order,"receiver_address"));
    }
    public AfterSaleView decide(Actor actor,String id,DecisionInput input) {
        new ApiId(id);return tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"support.after_sale.decide");var ref=sql.required("SELECT order_id FROM after_sale WHERE id=?",id);String orderId=text(ref,"order_id");sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",orderId);sql.required("SELECT id FROM order_item WHERE id=(SELECT order_item_id FROM after_sale WHERE id=?) FOR UPDATE",id);var state=sql.required("SELECT type FROM after_sale WHERE id=?",id);if(input.approved()&&"REFUND_ONLY".equals(text(state,"type")))reservations.lockOrderResources(orderId);var a=sql.required("SELECT * FROM after_sale WHERE id=? FOR UPDATE",id);
            if(!"REQUESTED".equals(text(a,"status"))) {if(actor.userId().equals(String.valueOf(a.get("reviewed_by")))&&Objects.equals(a.get("review_note"),input.note())&&(("APPROVED".equals(text(a,"status"))||"REFUNDING".equals(text(a,"status")))==input.approved()))return view(id);throw new BusinessException(ErrorCode.STATE_CONFLICT);}
            if(input.approved()) {
                if("REFUND_ONLY".equals(text(a,"type"))){refunds.createAfterSaleRefund(a);sql.update("UPDATE after_sale SET status='REFUNDING',reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3),review_note=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),input.note().trim(),id);audit.appendReason(actor.userId(),"after_sale.approve","after_sale",id,null,"SUCCESS","REFUND_ONLY");}
                else {sql.update("UPDATE after_sale SET status='APPROVED',reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3),review_note=?,return_deadline=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 7 DAY),updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),input.note().trim(),id);audit.appendReason(actor.userId(),"after_sale.approve","after_sale",id,null,"SUCCESS","RETURN_REFUND");}
            } else {sql.update("UPDATE after_sale SET status='REJECTED',reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3),review_note=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),input.note().trim(),id);audit.appendReason(actor.userId(),"after_sale.reject","after_sale",id,null,"SUCCESS",input.note().trim());}
            return view(id);});
    }
    public PageData<AfterSaleView> merchantList(Actor actor,String shop,String status,PageQuery page) {shopScope(actor,shop,"shop.after_sale.read");return page("o.shop_id=?",List.of(shop),status,page);}
    public AfterSaleView inspect(Actor actor,String shop,String id,InspectionInput input,String key,String trace) {
        new ApiId(id);return tx.run(()->{warehouseGuard(actor,shop);return idempotency.execute(actor,"API-51",key,List.of(shop,id,input.accepted(),input.restock(),input.note()),"after_sale",id,AfterSaleView.class,()->{
            var ref=sql.required("SELECT order_id,order_item_id FROM after_sale WHERE id=?",id);String orderId=text(ref,"order_id");sql.required("SELECT id FROM trade_order WHERE id=? AND shop_id=? FOR UPDATE",orderId,shop);sql.rows("SELECT id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);reservations.lockOrderResources(orderId);var a=sql.required("SELECT * FROM after_sale WHERE id=? AND order_id=? FOR UPDATE",id,orderId);
            if(!"RETURNING".equals(text(a,"status"))||!"RETURN_REFUND".equals(text(a,"type"))||input.restock()&&!input.accepted())throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(!input.accepted()){sql.update("UPDATE after_sale SET status='REJECTED',received_by=?,received_at=UTC_TIMESTAMP(3),inspection_note=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),input.note().trim(),id);audit.appendReason(actor.userId(),"after_sale.return_reject","after_sale",id,shop,"SUCCESS",input.note().trim());return view(id);}
            int quantity=((Number)a.get("quantity")).intValue();sql.update("UPDATE after_sale SET status='REFUNDING',received_by=?,received_at=UTC_TIMESTAMP(3),restock_quantity=?,inspection_note=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),input.restock()?quantity:0,input.note().trim(),id);
            if(input.restock())restock(text(ref,"order_item_id"),id,quantity,actor.userId(),trace,"RETURN_RESTOCK");refunds.createAfterSaleRefund(a);audit.appendReason(actor.userId(),"after_sale.return_inspect","after_sale",id,shop,"SUCCESS",input.restock()?"RESTOCK":"NOT_RESALEABLE");return view(id);
        });});
    }
    public int expireReturns(String owner) {
        return tx.run(()->{var lease=sql.one("SELECT * FROM job_lease WHERE job_name='after-sale-return-expiry' FOR UPDATE");if(lease.isEmpty())sql.insert("INSERT INTO job_lease(job_name,owner,lease_until) VALUES('after-sale-return-expiry',?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND))",owner);else {if(RowValues.instant(lease.get().get("lease_until")).isAfter(clock.instant()))return 0;sql.update("UPDATE job_lease SET owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",owner,lease.get().get("id"));}
            var rows=sql.rows("SELECT id,order_id,order_item_id FROM after_sale WHERE status='APPROVED' AND type='RETURN_REFUND' AND return_carrier IS NULL AND return_deadline<=UTC_TIMESTAMP(3) ORDER BY id LIMIT 100");int n=0;for(var r:rows){sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",r.get("order_id"));sql.required("SELECT id FROM order_item WHERE id=? FOR UPDATE",r.get("order_item_id"));sql.required("SELECT id FROM after_sale WHERE id=? FOR UPDATE",r.get("id"));if(sql.update("UPDATE after_sale SET status='EXPIRED',updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='APPROVED' AND return_carrier IS NULL",r.get("id"))==1)n++;}return n;});
    }
    private void restock(String item,String afterSale,int quantity,String actor,String trace,String type) {
        String sku=text(sql.required("SELECT sku_id FROM order_item WHERE id=?",item),"sku_id");inventory.restockAfterSale(sku,item,afterSale,type+":"+afterSale,quantity,type,actor,trace);
    }
    private PageData<AfterSaleView> page(String base,List<Object> baseArgs,String status,PageQuery page) {
        if(page.sort()!=null||status!=null&&!Set.of("REQUESTED","APPROVED","RETURNING","RECEIVED","REFUNDING","REJECTED","CANCELLED","COMPLETED","EXPIRED").contains(status))throw new BusinessException(ErrorCode.PARAM_INVALID);String where=" WHERE "+base;var args=new ArrayList<Object>(baseArgs);if(status!=null){where+=" AND a.status=?";args.add(status);}String from=" FROM after_sale a JOIN trade_order o ON o.id=a.order_id";
        long count=sql.count("SELECT COUNT(*)"+from+where,args.toArray());args.add(page.pageSize());args.add(page.offset());var ids=sql.rows("SELECT a.id"+from+where+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->text(r,"id")).toList();return new PageData<>(ids.stream().map(this::view).toList(),page.page(),page.pageSize(),count);
    }
    private AfterSaleView viewOwned(String user,String id) {var row=sql.required("SELECT id FROM after_sale WHERE id=? AND user_id=?",id,user);return view(text(row,"id"));}
    public AfterSaleView view(String id) {
        var a=sql.required("SELECT * FROM after_sale WHERE id=?",id);var evidence=sql.rows("SELECT file_id FROM after_sale_evidence WHERE after_sale_id=? ORDER BY file_id",id).stream().map(r->new ApiId(text(r,"file_id"))).toList();
        var refund=sql.one("SELECT * FROM refund WHERE after_sale_id=?",id).map(r->new RefundView(new ApiId(text(r,"id")),text(r,"refund_no"),new ApiId(text(r,"after_sale_id")),new ApiId(text(r,"payment_id")),text(r,"reason_type"),text(r,"status"),new ApiMoney((BigDecimal)r.get("amount")),(String)r.get("failure_code"),RowValues.instant(r.get("succeeded_at")))).orElse(null);
        return new AfterSaleView(new ApiId(id),text(a,"after_sale_no"),new ApiId(text(a,"order_id")),new ApiId(text(a,"order_item_id")),text(a,"type"),((Number)a.get("quantity")).intValue(),new ApiMoney((BigDecimal)a.get("requested_amount")),text(a,"reason"),text(a,"status"),evidence,(String)a.get("review_note"),RowValues.instant(a.get("return_deadline")),(String)a.get("return_carrier"),(String)a.get("return_tracking_no"),((Number)a.get("restock_quantity")).intValue(),refund,RowValues.instant(a.get("created_at")));
    }
    private void memberGuard(Actor actor){identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.after_sale");}
    private void shopScope(Actor actor,String shop,String permission){new ApiId(shop);policy.require(actor,permission);if(!identities.hasShopMembership(actor.userId(),shop,"MERCHANT")&&!identities.hasShopMembership(actor.userId(),shop,"WAREHOUSE"))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    private void warehouseGuard(Actor actor,String shop){identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.requireShop(actor,shop,"WAREHOUSE","shop.return.inspect");}
}
