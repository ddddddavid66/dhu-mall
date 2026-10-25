package com.yueji.mall.review.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.DatabaseTransaction;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.review.model.ReviewModels.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class ReviewService {
    private final SqlStore sql;private final DatabaseTransaction tx;private final IdentityRepository identities;private final IdentityPolicy policy;private final AuditRepository audit;private final Clock clock;
    public ReviewService(SqlStore sql,DatabaseTransaction tx,IdentityRepository identities,IdentityPolicy policy,AuditRepository audit,Clock clock){this.sql=sql;this.tx=tx;this.identities=identities;this.policy=policy;this.audit=audit;this.clock=clock;}
    public ReviewView create(Actor actor,ReviewInput input,String trace) {
        if(input.content().contains("<")||input.content().contains(">"))throw new BusinessException(ErrorCode.PARAM_INVALID);
        return tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.review");String itemId=input.orderItemId().value();String orderId=text(sql.required("SELECT order_id FROM order_item WHERE id=?",itemId),"order_id");
            var order=sql.required("SELECT * FROM trade_order WHERE id=? AND user_id=? FOR UPDATE",orderId,actor.userId());var item=sql.required("SELECT * FROM order_item WHERE id=? AND order_id=? FOR UPDATE",itemId,orderId);
            if(!"COMPLETED".equals(text(order,"status"))||order.get("completed_at")==null||Duration.between(RowValues.instant(order.get("completed_at")),clock.instant()).compareTo(Duration.ofDays(30))>0||((Number)item.get("refunded_quantity")).longValue()>=((Number)item.get("quantity")).longValue())throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(sql.count("SELECT COUNT(*) FROM review WHERE order_item_id=?",itemId)>0)throw new BusinessException(ErrorCode.STATE_CONFLICT);
            String id=sql.insert("INSERT INTO review(order_item_id,user_id,product_id,rating,content,status) VALUES(?,?,?,?,?,'VISIBLE')",itemId,actor.userId(),item.get("product_id"),input.rating(),input.content().trim());audit.append(actor.userId(),"review.create","review",id,null,"SUCCESS");return view(id);
        });
    }
    public ReviewView visibility(Actor actor,String id,VisibilityInput input) {new ApiId(id);return tx.run(()->{identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"review.moderate");var row=sql.required("SELECT * FROM review WHERE id=? FOR UPDATE",id);if(text(row,"status").equals(input.status())&&Objects.equals(row.get("moderation_reason"),input.reason()))return view(id);
        sql.update("UPDATE review SET status=?,moderated_by=?,moderation_reason=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",input.status(),actor.userId(),input.reason().trim(),id);audit.appendReason(actor.userId(),"review.visibility","review",id,null,"SUCCESS",input.status()+":"+input.reason().trim());return view(id);});}
    public PageData<ReviewView> list(Actor actor,String product,String status,PageQuery page) {policy.require(actor,"review.moderate");if(page.sort()!=null||product!=null&&!product.isBlank()&&status!=null&&!Set.of("VISIBLE","HIDDEN").contains(status))throw new BusinessException(ErrorCode.PARAM_INVALID);
        String where=" WHERE 1=1";var args=new ArrayList<Object>();if(product!=null&&!product.isBlank()){new ApiId(product);where+=" AND r.product_id=?";args.add(product);}if(status!=null){if(!Set.of("VISIBLE","HIDDEN").contains(status))throw new BusinessException(ErrorCode.PARAM_INVALID);where+=" AND r.status=?";args.add(status);}long total=sql.count("SELECT COUNT(*) FROM review r"+where,args.toArray());args.add(page.pageSize());args.add(page.offset());var ids=sql.rows("SELECT r.id FROM review r"+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->text(r,"id")).toList();return new PageData<>(ids.stream().map(this::view).toList(),page.page(),page.pageSize(),total);}
    private ReviewView view(String id){var r=sql.required("SELECT r.*,u.nickname FROM review r JOIN iam_user u ON u.id=r.user_id WHERE r.id=?",id);return new ReviewView(new ApiId(id),new ApiId(text(r,"product_id")),((Number)r.get("rating")).intValue(),text(r,"content"),text(r,"nickname"),RowValues.instant(r.get("created_at")),text(r,"status"));}
}
