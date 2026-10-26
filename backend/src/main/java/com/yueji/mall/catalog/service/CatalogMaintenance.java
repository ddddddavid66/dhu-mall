package com.yueji.mall.catalog.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.catalog.model.CatalogInputs.*;
import com.yueji.mall.catalog.model.CatalogViews.*;
import com.yueji.mall.catalog.repository.CatalogRepository;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.repository.AuditRepository;
import com.yueji.mall.platform.service.FileService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class CatalogMaintenance {
    private final SqlStore sql;
    private final CatalogRepository repository;
    private final IdentityRepository identities;
    private final IdentityPolicy policy;
    private final FileService files;
    private final AuditRepository audit;
    private final com.yueji.mall.platform.service.RedisSupport cache;
    public CatalogMaintenance(SqlStore sql,CatalogRepository repository,IdentityRepository identities,IdentityPolicy policy,FileService files,AuditRepository audit,com.yueji.mall.platform.service.RedisSupport cache) {
        this.cache=cache;
        this.sql=sql;this.repository=repository;this.identities=identities;this.policy=policy;this.files=files;this.audit=audit;
    }
    public List<CategoryView> categories(Actor actor,Status filter,boolean maintenance) {
        if(!maintenance && filter==null) return cache.cached("categories",java.time.Duration.ofSeconds(300+java.util.concurrent.ThreadLocalRandom.current().nextInt(31)),new com.fasterxml.jackson.core.type.TypeReference<List<CategoryView>>() {},()->categoryTree(actor,filter,false));
        return categoryTree(actor,filter,maintenance);
    }
    private List<CategoryView> categoryTree(Actor actor,Status filter,boolean maintenance) {
        if(maintenance) policy.require(actor,"catalog.category.manage");
        var rows=repository.categories();
        var visible=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            if(!maintenance && (!"ACTIVE".equals(row.get("status")) || row.get("parent_id")!=null && !"ACTIVE".equals(repository.category(row.get("parent_id").toString()).get("status")))) continue;
            visible.add(row);
        }
        // 后台筛选保留命中子节点的父节点作为树上下文，父节点真实状态仍返回。
        Set<String> wanted=new HashSet<>();
        for(var row:visible) if(filter==null || filter.name().equals(row.get("status"))) {
            wanted.add(text(row,"id")); if(row.get("parent_id")!=null) wanted.add(text(row,"parent_id"));
        }
        return visible.stream().filter(r->r.get("parent_id")==null && wanted.contains(text(r,"id")))
                .map(root->categoryView(root,visible.stream().filter(r->Objects.equals(r.get("parent_id"),root.get("id")) && wanted.contains(text(r,"id"))).map(r->categoryView(r,List.of())).toList())).toList();
    }
    private CategoryView categoryView(Map<String,Object> row,List<CategoryView> children) {
        return new CategoryView(new ApiId(text(row,"id")),row.get("parent_id")==null?null:new ApiId(text(row,"parent_id")),text(row,"name"),((Number)row.get("sort_order")).intValue(),text(row,"status"),children);
    }
    private void lock(Actor actor,String permission) {
        identities.lockAdminAnchor();identities.lockUser(actor.userId());policy.require(actor,permission);
        cache.invalidateAfterCommit();
    }
    @Transactional
    public CategoryView saveCategory(Actor actor,String id,CategoryInput input) {
        lock(actor,"catalog.category.manage"); if(id!=null) {new ApiId(id);repository.category(id);}
        if(input.parentId()!=null) {
            String parent=input.parentId().value();var row=repository.category(parent);
            if(parent.equals(id) || row.get("parent_id")!=null || id!=null && sql.count("SELECT COUNT(*) FROM category WHERE parent_id=?",id)>0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(input.status()==Status.ACTIVE && !"ACTIVE".equals(row.get("status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if(id!=null && input.status()==Status.DISABLED && sql.count("SELECT COUNT(*) FROM book b JOIN category c ON c.id=b.category_id WHERE b.status='ACTIVE' AND (c.id=? OR c.parent_id=?)",id,id)>0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        Object parent=input.parentId()==null?null:input.parentId().value();
        if(id==null) id=sql.insert("INSERT INTO category(parent_id,name,sort_order,status) VALUES(?,?,?,?)",parent,input.name(),input.sortOrder(),input.status().name());
        else sql.update("UPDATE category SET parent_id=?,name=?,sort_order=?,status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",parent,input.name(),input.sortOrder(),input.status().name(),id);
        audit.append(actor.userId(),"catalog.category.save","category",id,null,"SUCCESS");
        String saved=id;
        return categories(actor,null,true).stream().flatMap(c->java.util.stream.Stream.concat(java.util.stream.Stream.of(c),c.children().stream())).filter(c->c.id().value().equals(saved)).findFirst().orElseThrow();
    }
    public void activeCategory(String id) {
        var row=repository.category(id);
        if(!"ACTIVE".equals(row.get("status")) || row.get("parent_id")!=null && !"ACTIVE".equals(repository.category(text(row,"parent_id")).get("status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
    }
    public static String normalizeIsbn(String raw) {
        String isbn=raw.replace("-","");
        if(!isbn.matches("97[89][0-9]{10}")) throw new BusinessException(ErrorCode.PARAM_INVALID);
        int checksum=0;for(int i=0;i<13;i++) checksum+=(isbn.charAt(i)-'0')*(i%2==0?1:3);
        if(checksum%10!=0) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return isbn;
    }
    @Transactional
    public BookView saveBook(Actor actor,String id,BookInput input) {
        lock(actor,"catalog.book.write");String isbn=normalizeIsbn(input.isbn());
        if(input.status()==Status.ACTIVE) activeCategory(input.categoryId().value());else repository.category(input.categoryId().value());
        if(input.coverFileId()!=null) files.requireCover(input.coverFileId().value());
        if(id!=null) {
            new ApiId(id);var old=repository.book(id);
            if(!isbn.equals(old.get("isbn")) && sql.count("SELECT COUNT(*) FROM product WHERE book_id=?",id)>0) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            sql.update("UPDATE book SET isbn=?,title=?,author=?,publisher=?,publication_date=?,category_id=?,description=?,cover_file_id=?,status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",isbn,input.title(),input.author(),input.publisher(),input.publicationDate(),input.categoryId().value(),input.description(),input.coverFileId()==null?null:input.coverFileId().value(),input.status().name(),id);
        } else id=sql.insert("INSERT INTO book(isbn,title,author,publisher,publication_date,category_id,description,cover_file_id,status) VALUES(?,?,?,?,?,?,?,?,?)",isbn,input.title(),input.author(),input.publisher(),input.publicationDate(),input.categoryId().value(),input.description(),input.coverFileId()==null?null:input.coverFileId().value(),input.status().name());
        audit.append(actor.userId(),"catalog.book.save","book",id,null,"SUCCESS");return repository.bookView(repository.book(id),false);
    }
    public PageData<BookView> books(Actor actor,PageQuery page,String keyword) {
        policy.require(actor,"catalog.book.read"); if(page.sort()!=null) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return repository.books(page,keyword,actor.permissions().contains("catalog.book.write"));
    }
}
