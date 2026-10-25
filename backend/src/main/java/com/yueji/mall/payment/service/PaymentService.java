package com.yueji.mall.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.*;
import com.yueji.mall.payment.adapter.*;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.trade.repository.TradeRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class PaymentService {
    private final SqlStore sql;private final DatabaseTransaction transactions;private final IdentityRepository identities;private final IdentityPolicy policy;
    private final IdempotencyService idempotency;private final TradeRepository orders;private final PaymentRepository repository;private final PaymentSettings settings;
    private final AlipaySandboxAdapter alipay;private final LocalMockAdapter mock;private final PaymentFacts facts;private final ObjectMapper mapper;private final Clock clock;
    public PaymentService(SqlStore sql,DatabaseTransaction transactions,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,TradeRepository orders,
            PaymentRepository repository,PaymentSettings settings,AlipaySandboxAdapter alipay,LocalMockAdapter mock,PaymentFacts facts,ObjectMapper mapper,Clock clock) {
        this.sql=sql;this.transactions=transactions;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.orders=orders;this.repository=repository;
        this.settings=settings;this.alipay=alipay;this.mock=mock;this.facts=facts;this.mapper=mapper;this.clock=clock;
    }
    public PaymentAdapter adapter(Ticket ticket) {
        if("LOCAL_MOCK".equals(ticket.provider())) return mock;
        return alipay;
    }
    public Accepted create(Actor actor,String order,String key,String trace) {
        new ApiId(order);
        var accepted=transactions.run(()->{
            guard(actor);var result=idempotency.execute(actor,"API-32",key,List.of(order),"payment",null,Accepted.class,()->{
                var row=orders.owned(actor.userId(),order,true);
                if(!"PENDING_PAYMENT".equals(row.get("status")) || !RowValues.instant(row.get("expires_at")).isAfter(clock.instant())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
                var old=sql.one("SELECT * FROM payment WHERE order_id=?",order);boolean created=old.isEmpty();String id;
                if(created) id=sql.insert("INSERT INTO payment(payment_no,order_id,provider,status,amount) VALUES(?,?,?,'PENDING',?)","P"+UUID.randomUUID().toString().replace("-",""),order,settings.provider,row.get("payable_amount"));
                else {id=text(old.get(),"id");if(!List.of("INIT","PENDING").contains(text(old.get(),"status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);}
                return new Accepted(repository.view(id,null),created);
            });
            sql.update("UPDATE idempotency_record SET resource_id=? WHERE user_id=? AND operation_id='API-32' AND idempotency_key=?",result.view().id().value(),actor.userId(),key);return result;
        });
        // 此时 PENDING 和唯一 paymentNo 已提交；异常/进程中断仍可按原号查询。
        if(accepted.view().paymentAction()!=null) return accepted;
        String id=accepted.view().id().value();var ticket=repository.ticket(repository.row(id));
        if(!check(ticket,trace,true)) throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);
        var current=repository.row(id);PaymentAction action=null;
        var orderRow=sql.required("SELECT status,expires_at FROM trade_order WHERE id=?",order);
        if("PENDING".equals(current.get("status")) && "PENDING_PAYMENT".equals(orderRow.get("status")) && ticket.expiresAt().isAfter(clock.instant())) action=adapter(ticket).action(ticket);
        var result=new Accepted(repository.view(id,action),accepted.created());
        return transactions.run(()->{guard(actor);orders.owned(actor.userId(),order,true);
            var saved=sql.required("SELECT response_snapshot FROM idempotency_record WHERE user_id=? AND operation_id='API-32' AND idempotency_key=? FOR UPDATE",actor.userId(),key);
            try {
                var snapshot=mapper.readValue(text(saved,"response_snapshot"),Accepted.class);
                if(snapshot.view().paymentAction()!=null) return snapshot;
                sql.update("UPDATE idempotency_record SET resource_id=?,response_snapshot=?,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND operation_id='API-32' AND idempotency_key=?",id,mapper.writeValueAsString(result),actor.userId(),key);return result;
            }catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("支付幂等响应无法保存",error);}
        });
    }
    private void guard(Actor actor) {identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.payment");}
    public PaymentView view(Actor actor,String id,String trace) {
        policy.require(actor,"member.payment");var row=repository.owned(actor.userId(),id);
        // 查询只返回本地事实；查渠道由带退避/租约的后台任务完成，不能让 GET 放大渠道请求。
        return repository.view(text(row,"id"),null);
    }
    public PaymentView confirm(Actor actor,String id,String key,String trace) {
        if(!settings.mockEnabled) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var accepted=transactions.run(()->{guard(actor);return idempotency.execute(actor,"API-34",key,List.of(id),"payment",id,PaymentView.class,()->{
            var row=repository.owned(actor.userId(),id);var order=orders.owned(actor.userId(),text(row,"order_id"),true);
            boolean alreadyPaid="SUCCEEDED".equals(row.get("status")) && "PAID".equals(order.get("status"));
            if(!"LOCAL_MOCK".equals(row.get("provider")) || !alreadyPaid && !List.of("PENDING_PAYMENT","CLOSING").contains(text(order,"status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            return repository.view(id,null);
        });});
        if("SUCCEEDED".equals(accepted.status())) return accepted;
        var ticket=repository.ticket(repository.row(id));var fact=mock.confirm(ticket);facts.apply(id,fact,"confirm:"+ticket.paymentNo(),Crypto.hash("confirm:"+ticket.paymentNo()),trace);
        var result=repository.view(id,null);
        transactions.run(()->{guard(actor);try {sql.update("UPDATE idempotency_record SET response_snapshot=? WHERE user_id=? AND operation_id='API-34' AND idempotency_key=?",mapper.writeValueAsString(result),actor.userId(),key);}catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException(error);}return null;});return result;
    }
    public boolean check(Ticket ticket,String trace,boolean requestPath) {
        if(!claim(ticket.id())) return repository.row(ticket.id()).get("failure_code")==null;
        try {
            var fact=adapter(ticket).query(ticket);boolean closing=sql.count("SELECT COUNT(*) FROM trade_order WHERE id=? AND status='CLOSING'",ticket.orderId())>0;
            if(closing && fact.status()==FactStatus.WAITING) fact=adapter(ticket).close(ticket);
            if(fact.status()==FactStatus.UNKNOWN || fact.status()==FactStatus.NOT_FOUND) {failed(ticket.id());return fact.status()==FactStatus.NOT_FOUND;}
            facts.apply(ticket.id(),fact,"check:"+UUID.randomUUID(),Crypto.hash(fact.toString()),trace);
            sql.update("UPDATE payment SET failure_code=NULL WHERE id=?",ticket.id());
            return true;
        }catch(BusinessException error) {failed(ticket.id());if(requestPath) throw error;return false;}
    }
    private boolean claim(String id) {
        return transactions.run(()->{var row=sql.required("SELECT * FROM payment WHERE id=? FOR UPDATE",id);
            if(!List.of("INIT","PENDING").contains(text(row,"status"))) return false;
            int attempt=attempt(row);long delay=Math.min(300,30L<<Math.min(attempt,4));var last=RowValues.instant(row.get("last_checked_at"));
            if(last!=null && last.plusSeconds(delay).isAfter(clock.instant())) return false;
            sql.update("UPDATE payment SET last_checked_at=? WHERE id=?",Timestamp.from(clock.instant()),id);return true;});
    }
    private int attempt(Map<String,Object> row) {String failure=Objects.toString(row.get("failure_code"),"");return failure.matches("RETRY_[0-9]+")?Math.min(10,Integer.parseInt(failure.substring(6))):0;}
    private void failed(String id) {transactions.run(()->{var row=sql.required("SELECT * FROM payment WHERE id=? FOR UPDATE",id);sql.update("UPDATE payment SET failure_code=? WHERE id=?","RETRY_"+(attempt(row)+1),id);return null;});}
}
