package com.yueji.mall.payment.service;

import com.yueji.mall.aftersales.model.AfterSaleModels.RefundView;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.inventory.service.InventoryService;
import com.yueji.mall.inventory.service.ReservationService;
import com.yueji.mall.payment.adapter.PaymentAdapter;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.payment.service.PaymentService;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.trade.service.TradeRefundAccess;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class RefundService {
    private final SqlStore sql;private final DatabaseTransaction tx;private final IdentityRepository identities;private final IdentityPolicy policy;private final IdempotencyService idempotency;
    private final ReservationService reservations;private final InventoryService inventory;private final PaymentRepository payments;private final PaymentService paymentService;private final TradeRefundAccess trade;private final AuditRepository audit;private final Clock clock;
    public RefundService(SqlStore sql,DatabaseTransaction tx,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,ReservationService reservations,InventoryService inventory,PaymentRepository payments,PaymentService paymentService,TradeRefundAccess trade,AuditRepository audit,Clock clock){this.sql=sql;this.tx=tx;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.reservations=reservations;this.inventory=inventory;this.payments=payments;this.paymentService=paymentService;this.trade=trade;this.audit=audit;this.clock=clock;}
    public PageData<RefundView> list(Actor actor,String status,String shop,PageQuery page) {
        policy.require(actor,"finance.refund.read");if(page.sort()!=null||status!=null&&!Set.of("CREATED","PROCESSING","SUCCEEDED","FAILED").contains(status))throw new BusinessException(ErrorCode.PARAM_INVALID);
        String where=" WHERE 1=1";var args=new ArrayList<Object>();if(status!=null){where+=" AND r.status=?";args.add(status);}if(shop!=null){new ApiId(shop);where+=" AND o.shop_id=?";args.add(shop);}String from=" FROM refund r JOIN payment p ON p.id=r.payment_id JOIN trade_order o ON o.id=p.order_id";
        long total=sql.count("SELECT COUNT(*)"+from+where,args.toArray());args.add(page.pageSize());args.add(page.offset());var ids=sql.rows("SELECT r.id"+from+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->text(r,"id")).toList();return new PageData<>(ids.stream().map(this::view).toList(),page.page(),page.pageSize(),total);
    }
    /** Called from aftersales inside its transaction; refund records remain owned by payment. */
    @Transactional(propagation=Propagation.MANDATORY)
    public String createAfterSaleRefund(Map<String,Object> afterSale) {
        String orderId=text(afterSale,"order_id");reservations.lockOrderResources(orderId);
        var payment=sql.one("SELECT * FROM payment WHERE order_id=? FOR UPDATE",orderId).orElseThrow(()->new BusinessException(ErrorCode.STATE_CONFLICT));
        if(!"SUCCEEDED".equals(text(payment,"status")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
        String paymentId=text(payment,"id");BigDecimal active=(BigDecimal)sql.required("SELECT COALESCE(SUM(amount),0.00) amount FROM refund WHERE payment_id=? AND status IN ('CREATED','PROCESSING','SUCCEEDED')",paymentId).get("amount");BigDecimal amount=(BigDecimal)afterSale.get("requested_amount");
        if(active.add(amount).compareTo((BigDecimal)payment.get("amount"))>0)throw new BusinessException(ErrorCode.REFUND_LIMIT_EXCEEDED);
        String old=sql.one("SELECT id FROM refund WHERE after_sale_id=?",afterSale.get("id")).map(r->text(r,"id")).orElse(null);if(old!=null)return old;
        return sql.insert("INSERT INTO refund(refund_no,payment_id,after_sale_id,reason_type,amount) VALUES(?,?,?,'NORMAL_AFTER_SALE',?)","R"+UUID.randomUUID().toString().replace("-",""),paymentId,afterSale.get("id"),amount);
    }
    public RefundView execute(Actor actor,String id,String key,String trace) {
        new ApiId(id);boolean[] initiate={false};var accepted=tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"finance.refund.execute");return idempotency.execute(actor,"API-53",key,List.of(id),"refund",id,RefundView.class,()->{
            var ref=sql.required("SELECT r.*,p.order_id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",id);String orderId=text(ref,"order_id");var order=sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE",orderId);var items=sql.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);reservations.lockOrderResources(orderId);
            var payment=sql.required("SELECT * FROM payment WHERE id=? FOR UPDATE",ref.get("payment_id"));var refund=sql.required("SELECT * FROM refund WHERE id=? FOR UPDATE",id);
            if("LATE_PAYMENT_COMPENSATION".equals(text(refund,"reason_type")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(!"SUCCEEDED".equals(text(payment,"status")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var after=sql.required("SELECT * FROM after_sale WHERE id=? FOR UPDATE",refund.get("after_sale_id"));if(actor.userId().equals(String.valueOf(after.get("reviewed_by"))))throw new BusinessException(ErrorCode.ACCESS_DENIED);
            if("SUCCEEDED".equals(text(refund,"status")))return view(id);
            if("PROCESSING".equals(text(refund,"status")))throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            if(!Set.of("CREATED","FAILED").contains(text(refund,"status"))||!"REFUNDING".equals(text(after,"status")))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if("REFUND_ONLY".equals(text(after,"type"))&&(!"PAID".equals(text(order,"status"))||sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=?",orderId)>0))throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(((BigDecimal)refund.get("amount")).compareTo((BigDecimal)payment.get("amount"))>0)throw new BusinessException(ErrorCode.REFUND_LIMIT_EXCEEDED);
            sql.update("UPDATE refund SET status='PROCESSING',failure_code=NULL,requested_by=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",actor.userId(),id);initiate[0]=true;return view(id);
        });});
        var current=sql.required("SELECT r.*,p.order_id,p.payment_no,p.provider,p.amount payment_amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",id);
        if("SUCCEEDED".equals(text(current,"status"))||"FAILED".equals(text(current,"status")))return view(id);
        var payment=payments.row(text(current,"payment_id"));var ticket=payments.ticket(payment);PaymentAdapter adapter=paymentService.adapter(ticket);RefundFact fact;
        if(initiate[0])fact=adapter.refund(ticket,text(current,"refund_no"),(BigDecimal)current.get("amount"));
        else {fact=adapter.queryRefund(ticket,text(current,"refund_no"),(BigDecimal)current.get("amount"));if(fact.status()==RefundStatus.NOT_FOUND)fact=adapter.refund(ticket,text(current,"refund_no"),(BigDecimal)current.get("amount"));}
        final RefundFact channelFact=fact;
        if(!Objects.equals(channelFact.refundNo(),text(current,"refund_no"))||!Objects.equals(channelFact.paymentNo(),ticket.paymentNo())||channelFact.status()==RefundStatus.SUCCEEDED&&(channelFact.amount()==null||channelFact.amount().compareTo((BigDecimal)current.get("amount"))!=0)) {
            tx.run(()->{sql.update("INSERT IGNORE INTO reconciliation_issue(issue_key,issue_type,order_id,payment_id,refund_id,expected_amount,actual_amount) VALUES(?,?,?,?,?,?,?)","refund-fact:"+id,"REFUND_MISMATCH",current.get("order_id"),current.get("payment_id"),id,current.get("amount"),channelFact.amount());return null;});return view(id);
        }
        if(channelFact.status()==RefundStatus.SUCCEEDED)applySuccess(id,channelFact,trace);else if(channelFact.status()==RefundStatus.FAILED)markFailed(id,"CHANNEL_REFUND_FAILED");
        return view(id);
    }
    /** 由 reconciliation 对已核实渠道成功结果调用；不触发新的渠道请求。 */
    public void reconcileSuccess(String id,RefundFact fact,String trace){applySuccess(id,fact,trace);}
    public void reconcileFailed(String id){markFailed(id,"CHANNEL_REFUND_FAILED");}
    public RefundFact queryChannel(String id) {
        var r=sql.required("SELECT * FROM refund WHERE id=?",id);var payment=payments.row(text(r,"payment_id"));var ticket=payments.ticket(payment);return paymentService.adapter(ticket).queryRefund(ticket,text(r,"refund_no"),(BigDecimal)r.get("amount"));
    }
    private void applySuccess(String id,RefundFact fact,String trace) {
        var ref=sql.required("SELECT r.*,p.order_id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",id);String orderId=text(ref,"order_id");
        tx.run(()->{var order=sql.required("SELECT * FROM trade_order WHERE id=? FOR UPDATE",orderId);var items=sql.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",orderId);reservations.lockOrderResources(orderId);
            var payment=sql.required("SELECT * FROM payment WHERE id=? FOR UPDATE",ref.get("payment_id"));var refund=sql.required("SELECT * FROM refund WHERE id=? FOR UPDATE",id);
            if("SUCCEEDED".equals(text(refund,"status")))return null;if(!Set.of("PROCESSING","FAILED").contains(text(refund,"status"))||fact.status()!=RefundStatus.SUCCEEDED||!text(payment,"payment_no").equals(fact.paymentNo())||!text(refund,"refund_no").equals(fact.refundNo())||fact.amount()==null||fact.amount().compareTo((BigDecimal)refund.get("amount"))!=0)throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var after=sql.required("SELECT * FROM after_sale WHERE id=? FOR UPDATE",refund.get("after_sale_id"));String itemId=text(after,"order_item_id");var item=items.stream().filter(x->text(x,"id").equals(itemId)).findFirst().orElseThrow(()->new BusinessException(ErrorCode.STATE_CONFLICT));
            BigDecimal amount=(BigDecimal)refund.get("amount");int qty=((Number)after.get("quantity")).intValue();
            if("REFUND_ONLY".equals(text(after,"type"))) {
                if(!"PAID".equals(text(order,"status"))||sql.count("SELECT COUNT(*) FROM shipment WHERE order_id=?",orderId)>0||((Number)after.get("restock_quantity")).intValue()!=0)throw new BusinessException(ErrorCode.STATE_CONFLICT);
                String sku=text(item,"sku_id");inventory.restockAfterSale(sku,itemId,text(after,"id"),"REFUND_RESTOCK:"+id,qty,"REFUND_RESTOCK",text(refund,"requested_by"),trace);
            }
            trade.refunded(orderId,itemId,text(after,"id"),id,qty,amount,actorId(refund),trace);
            if(sql.update("UPDATE refund SET status='SUCCEEDED',channel_refund_no=?,succeeded_at=?,failure_code=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status IN ('PROCESSING','FAILED')",fact.channelNo(),Timestamp.from(clock.instant()),id)!=1)throw new BusinessException(ErrorCode.STATE_CONFLICT);
            sql.update("UPDATE after_sale SET status='COMPLETED',updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='REFUNDING'",after.get("id"));
            audit.append(actorId(refund),"refund.succeeded","refund",id,null,"SUCCESS");return null;
        });
    }
    private void markFailed(String id,String failure) {tx.run(()->{var r=sql.required("SELECT * FROM refund WHERE id=? FOR UPDATE",id);if("PROCESSING".equals(text(r,"status")))sql.update("UPDATE refund SET status='FAILED',failure_code=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",failure,id);return null;});}
    private String actorId(Map<String,Object> refund){return refund.get("requested_by")==null?null:text(refund,"requested_by");}
    public RefundView view(String id){var r=sql.required("SELECT * FROM refund WHERE id=?",id);Object after=r.get("after_sale_id");return new RefundView(new ApiId(id),text(r,"refund_no"),after==null?null:new ApiId(after.toString()),new ApiId(text(r,"payment_id")),text(r,"reason_type"),text(r,"status"),new ApiMoney((BigDecimal)r.get("amount")),(String)r.get("failure_code"),RowValues.instant(r.get("succeeded_at")));}
}
