package com.yueji.mall.payment.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.*;
import com.yueji.mall.inventory.service.ReservationService;
import com.yueji.mall.payment.adapter.PaymentAdapter;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.model.ReconciliationModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.platform.repository.AuditRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class ReconciliationService {
    private final SqlStore sql;private final DatabaseTransaction tx;private final IdentityRepository identities;private final IdentityPolicy policy;private final IdempotencyService idempotency;
    private final PaymentRepository payments;private final PaymentService paymentService;private final PaymentFacts paymentFacts;private final RefundService refunds;private final LatePaymentCompensation compensation;private final ReservationService reservations;private final AuditRepository audit;private final Clock clock;
    public ReconciliationService(SqlStore sql,DatabaseTransaction tx,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,PaymentRepository payments,PaymentService paymentService,PaymentFacts paymentFacts,RefundService refunds,LatePaymentCompensation compensation,ReservationService reservations,AuditRepository audit,Clock clock){this.sql=sql;this.tx=tx;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.payments=payments;this.paymentService=paymentService;this.paymentFacts=paymentFacts;this.refunds=refunds;this.compensation=compensation;this.reservations=reservations;this.audit=audit;this.clock=clock;}
    public PageData<ReconciliationView> runs(Actor actor,LocalDate billDate,PageQuery page){policy.require(actor,"finance.reconcile");if(page.sort()!=null)throw new BusinessException(ErrorCode.PARAM_INVALID);String where=" WHERE 1=1";var args=new ArrayList<Object>();if(billDate!=null){where+=" AND bill_date=?";args.add(billDate);}long total=sql.count("SELECT COUNT(*) FROM reconciliation_run"+where,args.toArray());args.add(page.pageSize());args.add(page.offset());var ids=sql.rows("SELECT id FROM reconciliation_run"+where+" ORDER BY bill_date DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->text(r,"id")).toList();return new PageData<>(ids.stream().map(this::runView).toList(),page.page(),page.pageSize(),total);}
    public ReconciliationView start(Actor actor,StartInput input,String key,String trace){
        if(!input.billDate().isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new BusinessException(ErrorCode.PARAM_INVALID);
        boolean[] claimed={false};var accepted=tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"finance.reconcile");return idempotency.execute(actor,"API-55",key,List.of(input.billDate(),input.provider()),"reconciliation",input.billDate()+":"+input.provider(),ReconciliationView.class,()->{
            var row=sql.one("SELECT * FROM reconciliation_run WHERE provider=? AND bill_date=? FOR UPDATE",input.provider(),input.billDate());String id;
            if(row.isEmpty())id=sql.insert("INSERT INTO reconciliation_run(provider,bill_date,status) VALUES(?,?,'RUNNING')",input.provider(),input.billDate());
            else {id=text(row.get(),"id");String status=text(row.get(),"status");if("SUCCEEDED".equals(status))return runView(id);if("RUNNING".equals(status)&&RowValues.instant(row.get().get("updated_at")).plus(Duration.ofMinutes(15)).isAfter(clock.instant()))return runView(id);
                sql.update("UPDATE reconciliation_run SET status='RUNNING',checked_count=0,difference_count=0,completed_at=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);}
            claimed[0]=true;return runView(id);
        });});
        String id=accepted.id().value();if(!claimed[0])return runView(id);
        int checked=0;
        try {
            LocalDateTime z0=input.billDate().atStartOfDay(),z1=input.billDate().plusDays(1).atStartOfDay();ZoneId zone=ZoneId.of("Asia/Shanghai");Timestamp begin=Timestamp.from(z0.atZone(zone).toInstant()),end=Timestamp.from(z1.atZone(zone).toInstant());
            var tickets=sql.rows("SELECT p.* FROM payment p WHERE p.provider=? AND ((p.paid_at>=? AND p.paid_at<?) OR (p.created_at>=? AND p.created_at<? AND p.status IN ('INIT','PENDING'))) ORDER BY p.id",input.provider(),begin,end,begin,end);
            if("ALIPAY_SANDBOX".equals(input.provider())&&tickets.isEmpty())throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);
            PaymentAdapter adapter=tickets.isEmpty()?null:paymentService.adapter(payments.ticket(tickets.get(0)));for(var p:tickets){checked++;var ticket=payments.ticket(p);Fact fact=adapter.query(ticket);comparePayment(id,input.billDate(),p,ticket,fact,trace);}
            var refundRows=sql.rows("SELECT r.* FROM refund r JOIN payment p ON p.id=r.payment_id WHERE p.provider=? AND ((r.succeeded_at>=? AND r.succeeded_at<?) OR (r.created_at>=? AND r.created_at<? AND r.status IN ('PROCESSING','FAILED'))) ORDER BY r.id",input.provider(),begin,end,begin,end);
            for(var r:refundRows){checked++;RefundFact fact=refunds.queryChannel(text(r,"id"));compareRefund(id,r,fact,trace);}
            finish(id,checked,actor.userId());return runView(id);
        } catch(RuntimeException error) {int completed=checked;tx.run(()->{sql.update("UPDATE reconciliation_run SET status='FAILED',checked_count=?,difference_count=(SELECT COUNT(*) FROM reconciliation_issue WHERE run_id=?),completed_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=?",completed,id,id);return null;});return runView(id);}
    }
    public PageData<IssueView> issues(Actor actor,String status,PageQuery page){policy.require(actor,"finance.reconcile");if(page.sort()!=null||status!=null&&!Set.of("OPEN","RESOLVED").contains(status))throw new BusinessException(ErrorCode.PARAM_INVALID);String where=status==null?"":" WHERE status=?";Object[] filter=status==null?new Object[]{}:new Object[]{status};long total=sql.count("SELECT COUNT(*) FROM reconciliation_issue"+where,filter);var args=new ArrayList<Object>(Arrays.asList(filter));args.add(page.pageSize());args.add(page.offset());var ids=sql.rows("SELECT id FROM reconciliation_issue"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->text(r,"id")).toList();return new PageData<>(ids.stream().map(this::issueView).toList(),page.page(),page.pageSize(),total);}
    public IssueView resolve(Actor actor,String id,IssueResolutionInput input){new ApiId(id);return tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"finance.reconcile");var ref=sql.required("SELECT * FROM reconciliation_issue WHERE id=?",id);Object orderId=ref.get("order_id");if(orderId==null&&ref.get("payment_id")!=null)orderId=sql.one("SELECT order_id FROM payment WHERE id=?",ref.get("payment_id")).map(r->r.get("order_id")).orElse(null);if(orderId==null&&ref.get("refund_id")!=null)orderId=sql.one("SELECT p.order_id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",ref.get("refund_id")).map(r->r.get("order_id")).orElse(null);if(orderId!=null){String order=orderId.toString();sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",order);sql.rows("SELECT id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",order);reservations.lockOrderResources(order);}var r=sql.required("SELECT * FROM reconciliation_issue WHERE id=? FOR UPDATE",id);if("RESOLVED".equals(text(r,"status")))return issueView(id);
        String resolution=input.resolution().trim()+"\nEvidence: "+input.evidenceReference().trim();sql.update("UPDATE reconciliation_issue SET status='RESOLVED',resolution=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='OPEN'",resolution,actor.userId(),id);audit.appendReason(actor.userId(),"reconciliation.issue.resolve","reconciliation_issue",id,null,"SUCCESS",input.evidenceReference().trim());return issueView(id);});}
    private void comparePayment(String run,LocalDate date,Map<String,Object> p,Ticket ticket,Fact fact,String trace){
        String id=text(p,"id");if(fact.status()==FactStatus.PAID||fact.status()==FactStatus.CLOSED){String key="recon:"+date+":"+ticket.provider()+":"+id;boolean applied=paymentFacts.apply(id,fact,key,Crypto.hash(fact.toString()),trace);if(!applied)issue(run,"PAYMENT_FACT_MISMATCH",p.get("order_id"),id,null,p.get("amount"),fact.amount(),"payment:"+key);}
        else if(fact.status()==FactStatus.UNKNOWN||fact.status()==FactStatus.NOT_FOUND)issue(run,"PAYMENT_QUERY_UNKNOWN",p.get("order_id"),id,null,p.get("amount"),fact.amount(),"payment-query:"+date+":"+id);
        var current=sql.required("SELECT * FROM payment WHERE id=?",id);boolean mismatch="SUCCEEDED".equals(text(current,"status"))&&fact.status()!=FactStatus.PAID||"CLOSED".equals(text(current,"status"))&&fact.status()!=FactStatus.CLOSED||fact.status()==FactStatus.PAID&&fact.amount()!=null&&fact.amount().compareTo((BigDecimal)current.get("amount"))!=0;
        if(mismatch)issue(run,"PAYMENT_STATUS_MISMATCH",p.get("order_id"),id,null,p.get("amount"),fact.amount(),"payment-status:"+date+":"+id);
    }
    private void compareRefund(String run,Map<String,Object> r,RefundFact fact,String trace){
        var p=sql.required("SELECT * FROM payment WHERE id=?",r.get("payment_id"));boolean identity=Objects.equals(fact.refundNo(),text(r,"refund_no"))&&Objects.equals(fact.paymentNo(),text(p,"payment_no"));
        if("LATE_PAYMENT_COMPENSATION".equals(text(r,"reason_type"))){
            if(fact.status()==RefundStatus.SUCCEEDED&&identity&&fact.amount()!=null&&fact.amount().compareTo((BigDecimal)r.get("amount"))==0){compensation.reconcileSuccess(text(r,"id"),fact,trace);return;}
            if(fact.status()==RefundStatus.UNKNOWN||fact.status()==RefundStatus.NOT_FOUND||!identity||fact.status()==RefundStatus.SUCCEEDED||"SUCCEEDED".equals(text(r,"status")))issue(run,"LATE_REFUND_STATUS_MISMATCH",p.get("order_id"),r.get("payment_id"),r.get("id"),r.get("amount"),fact.amount(),"late-refund:"+text(r,"id"));
            return;
        }
        if(fact.status()==RefundStatus.SUCCEEDED&&identity&&fact.amount()!=null&&fact.amount().compareTo((BigDecimal)r.get("amount"))==0){refunds.reconcileSuccess(text(r,"id"),fact,trace);return;}
        if(fact.status()==RefundStatus.FAILED&&identity){refunds.reconcileFailed(text(r,"id"));return;}
        if(fact.status()==RefundStatus.UNKNOWN||fact.status()==RefundStatus.NOT_FOUND||!identity||fact.status()==RefundStatus.SUCCEEDED||"SUCCEEDED".equals(text(r,"status")))issue(run,"REFUND_STATUS_MISMATCH",p.get("order_id"),r.get("payment_id"),r.get("id"),r.get("amount"),fact.amount(),"refund:"+text(r,"id"));
    }
    private void issue(String run,String type,Object order,Object payment,Object refund,Object expected,Object actual,String key){
        tx.run(()->{
            Object orderId=order;
            if(orderId==null&&payment!=null)orderId=sql.one("SELECT order_id FROM payment WHERE id=?",payment).map(r->r.get("order_id")).orElse(null);
            if(orderId==null&&refund!=null)orderId=sql.one("SELECT p.order_id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=?",refund).map(r->r.get("order_id")).orElse(null);
            if(orderId!=null){String id=orderId.toString();sql.required("SELECT id FROM trade_order WHERE id=? FOR UPDATE",id);sql.rows("SELECT id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",id);reservations.lockOrderResources(id);}
            if(payment!=null)sql.required("SELECT id FROM payment WHERE id=? FOR UPDATE",payment);
            if(refund!=null)sql.required("SELECT id FROM refund WHERE id=? FOR UPDATE",refund);
            sql.update("INSERT IGNORE INTO reconciliation_issue(run_id,order_id,payment_id,refund_id,issue_key,issue_type,expected_amount,actual_amount) VALUES(?,?,?,?,?,?,?,?)",run,orderId,payment,refund,key,type,expected,actual);return null;
        });
    }
    private void finish(String id,int checked,String actor){tx.run(()->{sql.required("SELECT id FROM reconciliation_run WHERE id=? FOR UPDATE",id);long differences=sql.count("SELECT COUNT(*) FROM reconciliation_issue WHERE run_id=? AND status='OPEN'",id);sql.update("UPDATE reconciliation_run SET status='SUCCEEDED',checked_count=?,difference_count=?,completed_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=?",checked,differences,id);audit.append(actor,"reconciliation.complete","reconciliation_run",id,null,"SUCCESS");return null;});}
    private ReconciliationView runView(String id){var r=sql.required("SELECT * FROM reconciliation_run WHERE id=?",id);Object date=r.get("bill_date");return new ReconciliationView(new ApiId(id),text(r,"provider"),date instanceof java.sql.Date d?d.toLocalDate():(LocalDate)date,text(r,"status"),((Number)r.get("checked_count")).intValue(),((Number)r.get("difference_count")).intValue(),RowValues.instant(r.get("completed_at")));}
    private IssueView issueView(String id){var r=sql.required("SELECT * FROM reconciliation_issue WHERE id=?",id);return new IssueView(new ApiId(id),text(r,"issue_type"),id(r.get("order_id")),id(r.get("payment_id")),id(r.get("refund_id")),money(r.get("expected_amount")),money(r.get("actual_amount")),text(r,"status"),(String)r.get("resolution"),RowValues.instant(r.get("created_at")));}
    private ApiId id(Object value){return value==null?null:new ApiId(value.toString());}private ApiMoney money(Object value){return value==null?null:new ApiMoney((BigDecimal)value);}
}
