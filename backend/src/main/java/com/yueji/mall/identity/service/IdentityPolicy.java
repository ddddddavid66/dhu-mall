package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class IdentityPolicy {
    private final IdentityRepository identities;
    public IdentityPolicy(IdentityRepository identities) { this.identities=identities; }
    public void current(Actor actor) {
        var user=identities.user(actor.userId());
        if (!"ACTIVE".equals(text(user,"status"))) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        if (identities.authVersion(user)!=actor.authVersion()) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        // 过滤器之后若会话已退出，事务内再次鉴权也必须拒绝，不能只比较 authVersion。
        if (!identities.hasLiveFamily(actor.userId(),actor.familyNo(),actor.authVersion())) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
    }
    public void require(Actor actor,String permission) {
        current(actor);
        if (!identities.permissions(actor.userId()).contains(permission)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }
    public void requireShop(Actor actor,String shopId,String duty,String permission) {
        require(actor,permission);
        new ApiId(shopId);
        if (!identities.hasShopMembership(actor.userId(),shopId,duty)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    public void lockAdminChanges(Actor actor,String permission) {
        // 使用已有 ADMIN 角色行作全局授权写锁，防止两个管理员并发互相撤销导致无人可恢复。
        identities.lockAdminAnchor();
        require(actor,permission);
    }
    public void ensureLastAdministrator() {
        long count=identities.effectiveAdministrators();
        if (count==0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
    }
}
