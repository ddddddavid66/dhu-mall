package com.yueji.mall.cart.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.cart.model.CartModels.*;
import com.yueji.mall.cart.repository.CartRepository;
import com.yueji.mall.catalog.repository.CatalogRepository;
import com.yueji.mall.catalog.service.CatalogAccess;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class CartService {
    private final CartRepository repository;private final CatalogAccess catalog;private final IdentityPolicy policy;private final IdentityRepository identities;
    public CartService(CartRepository repository,CatalogAccess catalog,IdentityPolicy policy,IdentityRepository identities) {this.repository=repository;this.catalog=catalog;this.policy=policy;this.identities=identities;}
    public CartView list(Actor actor) {
        policy.require(actor,"member.cart");var groups=new LinkedHashMap<String,List<CartItemView>>();var names=new HashMap<String,String>();
        for(var cart:repository.list(actor.userId())) {
            var sku=catalog.sku(text(cart,"sku_id"));String shop=text(sku,"shop_id");int quantity=((Number)cart.get("quantity")).intValue();
            boolean visible=CatalogRepository.visible(sku);long available=CatalogRepository.available(sku);boolean buy=visible && available>=quantity;
            Object cover=sku.get("cover_file_id");
            var item=new CartItemView(new ApiId(text(cart,"id")),new ApiId(text(cart,"sku_id")),new ApiId(text(sku,"id")),text(sku,"title"),cover==null?null:"/api/v1/files/"+cover+"/content",new ApiMoney((BigDecimal)sku.get("sale_price")),quantity,((Number)cart.get("selected")).intValue()==1,available,buy,buy?null:visible?"OUT_OF_STOCK":"PRODUCT_UNAVAILABLE");
            groups.computeIfAbsent(shop,key->new ArrayList<>()).add(item);names.put(shop,text(sku,"shop_name"));
        }
        return new CartView(groups.entrySet().stream().map(e->new CartGroupView(new ApiId(e.getKey()),names.get(e.getKey()),e.getValue())).toList());
    }
    @Transactional
    public CartView set(Actor actor,String sku,CartSet input) {
        identities.lockUser(actor.userId());policy.require(actor,"member.cart");
        var item=catalog.sku(sku);
        // 已失效的旧项仍允许取消勾选；新加入或重新勾选必须通过当前可购买校验。
        if(input.selected() || !repository.contains(actor.userId(),sku)) catalog.requirePurchasable(item,input.quantity());
        repository.set(actor.userId(),sku,input.quantity(),input.selected());return list(actor);
    }
    @Transactional
    public void delete(Actor actor,String sku) {new ApiId(sku);identities.lockUser(actor.userId());policy.require(actor,"member.cart");repository.delete(actor.userId(),sku);}
}
