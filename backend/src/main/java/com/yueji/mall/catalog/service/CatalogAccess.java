package com.yueji.mall.catalog.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.catalog.repository.CatalogRepository;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.service.IdentityPolicy;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

/** 跨模块只读边界，购物车和库存不直接写 catalog 表。 */
@Service
public class CatalogAccess {
    private final CatalogRepository repository;
    private final IdentityPolicy policy;
    public CatalogAccess(CatalogRepository repository,IdentityPolicy policy) {this.repository=repository;this.policy=policy;}
    public Map<String,Object> sku(String id) {new ApiId(id);return repository.sku(id);}
    public String coverStorageKey(Map<String,Object> sku) {return repository.coverStorageKey(sku.get("cover_file_id"));}
    public Map<String,Object> ownedSku(Actor actor,String shop,String sku,String permission) {
        policy.requireShop(actor,shop,"MERCHANT",permission); var row=sku(sku);
        if(!text(row,"shop_id").equals(shop)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return row;
    }
    public Map<String,Object> ownedProduct(Actor actor,String shop,String id,String permission) {
        policy.requireShop(actor,shop,"MERCHANT",permission);new ApiId(id);var row=repository.product(id);
        if(!text(row,"shop_id").equals(shop)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return row;
    }
    public void requirePurchasable(Map<String,Object> row,int quantity) {
        if(!CatalogRepository.visible(row)) throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        if(CatalogRepository.available(row)<quantity) throw new BusinessException(ErrorCode.OUT_OF_STOCK);
    }
}
