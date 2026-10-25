package com.yueji.mall.cart.service;

import com.yueji.mall.cart.model.AddressInput;
import com.yueji.mall.cart.model.AddressView;
import com.yueji.mall.cart.repository.AddressRepository;
import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressService {
    private final AddressRepository addresses;
    private final IdentityRepository identities;
    private final IdentityPolicy policy;
    private final AuditRepository audit;
    public AddressService(AddressRepository addresses,IdentityRepository identities,IdentityPolicy policy,AuditRepository audit) {
        this.addresses=addresses; this.identities=identities; this.policy=policy; this.audit=audit;
    }
    public List<AddressView> list(Actor actor) { policy.require(actor,"member.address"); return addresses.list(actor.userId()); }
    @Transactional
    public AddressView create(Actor actor,AddressInput input) {
        identities.lockUser(actor.userId());
        policy.require(actor,"member.address");
        if (addresses.list(actor.userId()).size()>=20) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if (input.isDefault()) addresses.clearDefault(actor.userId());
        var result=addresses.create(actor.userId(),input);
        audit.append(actor.userId(),"address.create","address",result.id().value(),null,"SUCCESS");
        return result;
    }
    @Transactional
    public AddressView update(Actor actor,String id,AddressInput input) {
        lockOwned(actor,id,false);
        if (input.isDefault()) addresses.clearDefault(actor.userId());
        var result=addresses.update(actor.userId(),id,input);
        audit.append(actor.userId(),"address.update","address",id,null,"SUCCESS");
        return result;
    }
    @Transactional
    public void delete(Actor actor,String id) {
        lockOwned(actor,id,true);
        addresses.delete(actor.userId(),id);
        audit.append(actor.userId(),"address.delete","address",id,null,"SUCCESS");
    }
    private void lockOwned(Actor actor,String id,boolean allowDeleted) {
        new ApiId(id);
        identities.lockUser(actor.userId());
        policy.require(actor,"member.address");
        var row=addresses.owned(actor.userId(),id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!allowDeleted && !"ACTIVE".equals(row.get("status"))) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
