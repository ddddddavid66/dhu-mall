package com.yueji.mall.trade.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.trade.model.TradeModels.*;
import com.yueji.mall.trade.repository.TradeRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final TradeRepository repository;private final IdentityRepository identities;private final IdentityPolicy policy;
    private final DatabaseTransaction transactions;private final IdempotencyService idempotency;private final SqlStore sql;
    public OrderService(TradeRepository repository,IdentityRepository identities,IdentityPolicy policy,DatabaseTransaction transactions,IdempotencyService idempotency,SqlStore sql) {
        this.repository=repository;this.identities=identities;this.policy=policy;this.transactions=transactions;this.idempotency=idempotency;this.sql=sql;
    }
    public BatchView batch(Actor actor,String id) {policy.require(actor,"member.order");return repository.batch(actor.userId(),id);}
    public PageData<OrderSummary> list(Actor actor,OrderStatus status,String orderNo,PageQuery page) {policy.require(actor,"member.order");return repository.list(actor.userId(),status,orderNo,page);}
    public OrderView view(Actor actor,String id) {policy.require(actor,"member.order");return repository.view(actor.userId(),id);}
    public OrderView cancel(Actor actor,String id,CancelInput input,String key,String trace) {
        new ApiId(id);
        return transactions.run(()-> {
            identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.order");
            return idempotency.execute(actor,"API-31",key,List.of(id,input.reason()),"order",id,OrderView.class,()-> {
                var order=repository.owned(actor.userId(),id,true);String status=SqlStore.text(order,"status");
                if("PENDING_PAYMENT".equals(status)) closing(id,actor.userId(),input.reason(),"CANCEL_REQUEST",trace);
                else if(!"CLOSING".equals(status) && !"CANCELLED".equals(status)) throw new BusinessException(ErrorCode.STATE_CONFLICT);
                return repository.view(actor.userId(),id);
            });
        });
    }
    /** 已持有订单锁；这里只记录关闭意图，渠道确认和库存释放交给阶段 E。 */
    void closing(String id,String actor,String reason,String action,String trace) {
        if(sql.update("UPDATE trade_order SET status='CLOSING',close_reason=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PENDING_PAYMENT'",reason,id)!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        repository.log(id,"PENDING_PAYMENT","CLOSING",action,actor,reason,trace);
    }
}
