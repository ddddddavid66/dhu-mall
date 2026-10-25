package com.yueji.mall.platform.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditQueries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditQueries queries;
    private final IdentityPolicy policy;
    private final Clock clock;
    public AuditService(AuditQueries queries,IdentityPolicy policy,Clock clock) { this.queries=queries; this.policy=policy; this.clock=clock; }
    @Transactional(readOnly=true)
    public PageData<AuditQueries.AuditView> list(Actor actor,PageQuery page,String actorId,String action,String traceId,Instant from,Instant to) {
        policy.require(actor,"platform.audit.read");
        if (actorId!=null) new ApiId(actorId);
        if (action!=null && action.length()>80 || traceId!=null && traceId.length()>64 || page.sort()!=null) throw new BusinessException(ErrorCode.PARAM_INVALID);
        to=to==null ? clock.instant() : to;
        from=from==null ? to.minus(Duration.ofDays(7)) : from;
        if (!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(90))>0) throw new BusinessException(ErrorCode.PARAM_INVALID);
        var filter=new AuditQueries.Filter(actorId,action,traceId,from,to);
        return new PageData<>(queries.list(filter,page),page.page(),page.pageSize(),queries.count(filter));
    }
}
