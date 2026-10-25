package com.yueji.mall.catalog.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.catalog.model.CatalogInputs.*;
import com.yueji.mall.catalog.model.CatalogViews.*;
import com.yueji.mall.catalog.repository.CatalogRepository;
import com.yueji.mall.catalog.repository.CatalogRepository.Filter;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.inventory.service.InventoryService;
import com.yueji.mall.platform.repository.AuditRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class ProductService {
    private final SqlStore sql;private final CatalogRepository repository;private final CatalogAccess access;
    private final CatalogMaintenance maintenance;private final IdentityRepository identities;private final IdentityPolicy policy;
    private final InventoryService inventory;private final AuditRepository audit;
    public ProductService(SqlStore sql,CatalogRepository repository,CatalogAccess access,CatalogMaintenance maintenance,
            IdentityRepository identities,IdentityPolicy policy,InventoryService inventory,AuditRepository audit) {
        this.sql=sql;this.repository=repository;this.access=access;this.maintenance=maintenance;
        this.identities=identities;this.policy=policy;this.inventory=inventory;this.audit=audit;
    }
    public PageData<ProductView> list(Actor actor,String shop,String status,String keyword,String category,Boolean featured,PageQuery page,boolean publicOnly,boolean ops) {
        if(ops) policy.require(actor,"catalog.moderate");
        else if(!publicOnly) policy.requireShop(actor,shop,"MERCHANT","shop.product.read");
        if(!publicOnly && page.sort()!=null) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return repository.products(new Filter(shop,status,keyword,category,featured,publicOnly),page);
    }
    public ProductView publicProduct(String id) {
        new ApiId(id);var row=repository.product(id);
        if(!CatalogRepository.visible(row)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return repository.view(row);
    }
    public PageData<ReviewView> reviews(String id,PageQuery page) {
        publicProduct(id);if(page.sort()!=null) throw new BusinessException(ErrorCode.PARAM_INVALID);
        var list=sql.rows("SELECT r.*,u.nickname FROM review r JOIN iam_user u ON u.id=r.user_id WHERE r.product_id=? AND r.status='VISIBLE' ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",id,page.pageSize(),page.offset()).stream().map(r->new ReviewView(new ApiId(text(r,"id")),new ApiId(id),((Number)r.get("rating")).intValue(),text(r,"content"),text(r,"nickname"),RowValues.instant(r.get("created_at")),"VISIBLE")).toList();
        return new PageData<>(list,page.page(),page.pageSize(),sql.count("SELECT COUNT(*) FROM review WHERE product_id=? AND status='VISIBLE'",id));
    }
    private void lock(Actor actor) {identities.lockAdminAnchor();identities.lockUser(actor.userId());policy.current(actor);}
    private void price(ApiMoney value) {if(value.value().signum()<=0) throw new BusinessException(ErrorCode.PARAM_INVALID);}
    @Transactional
    public ProductView create(Actor actor,String shop,ProductInput input) {
        lock(actor);policy.requireShop(actor,shop,"MERCHANT","shop.product.write");price(input.salePrice());
        var book=repository.book(input.bookId().value());
        if(!"ACTIVE".equals(book.get("status"))) throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        maintenance.activeCategory(text(book,"category_id"));
        String id=sql.insert("INSERT INTO product(shop_id,book_id,description) VALUES(?,?,?)",shop,input.bookId().value(),input.description());
        String sku=sql.insert("INSERT INTO sku(product_id,sku_code,sale_price) VALUES(?,?,?)",id,input.skuCode(),input.salePrice().value());
        inventory.initialize(sku);audit.append(actor.userId(),"product.create","product",id,shop,"SUCCESS");return repository.view(repository.product(id));
    }
    private Map<String,Object> owned(Actor actor,String shop,String id,long version) {
        var row=access.ownedProduct(actor,shop,id,"shop.product.write");repository.lockProduct(id);
        if(CatalogRepository.number(row,"version")!=version) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return row;
    }
    @Transactional
    public ProductView edit(Actor actor,String shop,String id,ProductEdit input) {
        lock(actor);var row=owned(actor,shop,id,input.version());price(input.salePrice());
        sql.update("UPDATE product SET description=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",input.description(),id);
        if(((BigDecimal)row.get("sale_price")).compareTo(input.salePrice().value())!=0) sql.update("UPDATE sku SET sale_price=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE product_id=?",input.salePrice().value(),id);
        audit.append(actor.userId(),"product.update","product",id,shop,"SUCCESS");return repository.view(repository.product(id));
    }
    @Transactional
    public ProductView status(Actor actor,String shop,String id,ProductStatusInput input) {
        lock(actor);var row=owned(actor,shop,id,input.version());
        if(input.status()==ProductStatus.DRAFT) throw new BusinessException(ErrorCode.PARAM_INVALID);
        if(input.status()==ProductStatus.ON_SALE) {
            if(!"ACTIVE".equals(row.get("shop_status")) || !"ACTIVE".equals(row.get("book_status"))) throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
            maintenance.activeCategory(text(row,"category_id"));
        }
        if(!input.status().name().equals(row.get("status"))) sql.update("UPDATE product SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",input.status().name(),id);
        audit.append(actor.userId(),"product.status","product",id,shop,"SUCCESS");return repository.view(repository.product(id));
    }
    @Transactional
    public ProductView moderate(Actor actor,String id,ModerationInput input) {
        lock(actor);policy.require(actor,"catalog.moderate");new ApiId(id);repository.lockProduct(id);var row=repository.product(id);
        if(CatalogRepository.number(row,"version")!=input.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        String next=input.offShelf()?"OFF_SHELF":text(row,"status");
        // offShelf=false 只撤销本次下架意图，绝不据此强制 ON_SALE；重新上架始终由本店商家显式操作。
        sql.update("UPDATE product SET status=?,featured=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",next,input.featured(),id);
        audit.appendReason(actor.userId(),"product.moderate","product",id,text(row,"shop_id"),"SUCCESS",input.reason());return repository.view(repository.product(id));
    }
}
