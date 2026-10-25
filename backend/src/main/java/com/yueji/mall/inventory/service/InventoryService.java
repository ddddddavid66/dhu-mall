package com.yueji.mall.inventory.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.service.IdempotencyService;
import com.yueji.mall.catalog.service.CatalogAccess;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.inventory.model.InventoryModels.*;
import com.yueji.mall.inventory.repository.InventoryRepository;
import com.yueji.mall.platform.repository.AuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InventoryService {
    private final InventoryRepository repository;private final CatalogAccess catalog;private final IdentityRepository identities;
    private final IdentityPolicy policy;private final IdempotencyService idempotency;private final AuditRepository audit;
    public InventoryService(InventoryRepository repository,CatalogAccess catalog,IdentityRepository identities,IdentityPolicy policy,IdempotencyService idempotency,AuditRepository audit) {
        this.repository=repository;this.catalog=catalog;this.identities=identities;this.policy=policy;this.idempotency=idempotency;this.audit=audit;
    }
    @Transactional
    public void initialize(String sku) {repository.initialize(sku);}
    /** 退货验收或退款成功时由库存所有者执行；调用方须已按全局顺序锁定订单及订单行。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void restockAfterSale(String sku,String orderItem,String afterSale,String businessKey,int quantity,String movementType,String actor,String trace) {
        if(quantity<=0||!List.of("RETURN_RESTOCK","REFUND_RESTOCK").contains(movementType))throw new BusinessException(ErrorCode.PARAM_INVALID);
        var before=repository.view(repository.lock(sku));if(sqlCountMovement(businessKey)>0)return;
        if(before.onHand()+quantity>4294967295L||repositoryAdjustRestock(sku,quantity)!=1)throw new BusinessException(ErrorCode.STATE_CONFLICT);
        var after=repository.view(repository.lock(sku));
        sqlMovement(sku,orderItem,afterSale,movementType,businessKey,quantity,after,actor,trace);
    }
    private long sqlCountMovement(String key){return repository.countMovement(key);}
    private int repositoryAdjustRestock(String sku,int quantity){return repository.restock(sku,quantity);}
    private void sqlMovement(String sku,String item,String afterSale,String type,String key,int quantity,InventoryView after,String actor,String trace){repository.restockMovement(sku,item,afterSale,type,key,quantity,after,actor,trace);}
    public PageData<InventoryView> list(Actor actor,String shop,String sku,PageQuery page) {
        policy.requireShop(actor,shop,"MERCHANT","shop.inventory.read");
        if(page.sort()!=null) throw new BusinessException(ErrorCode.PARAM_INVALID);
        if(sku!=null) catalog.ownedSku(actor,shop,sku,"shop.inventory.read");return repository.list(shop,sku,page);
    }
    @Transactional
    public InventoryView adjust(Actor actor,String shop,String sku,Adjustment input,String key,String trace) {
        identities.lockUser(actor.userId());catalog.ownedSku(actor,shop,sku,"shop.inventory.adjust");
        if(input.delta()==0) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return idempotency.execute(actor,"API-20",key,List.of(shop,sku,input.delta(),input.reason(),input.version()),"sku",sku,InventoryView.class,()-> {
            var before=repository.view(repository.lock(sku));
            if(before.version()!=input.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            long next=before.onHand()+input.delta();
            if(next<before.reserved() || next>4294967295L) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            repository.adjust(sku,input.version(),input.delta());var after=repository.view(repository.lock(sku));
            repository.movement(sku,input.delta(),after,"API20:"+actor.userId()+":"+key,input.reason(),actor.userId(),trace);
            audit.append(actor.userId(),"inventory.adjust","sku",sku,shop,"SUCCESS");return after;
        });
    }
}
