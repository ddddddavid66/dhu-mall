package com.yueji.mall.catalog.repository;

import com.yueji.mall.common.api.*;
import com.yueji.mall.catalog.model.CatalogViews.*;
import com.yueji.mall.common.persistence.SqlStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class CatalogRepository {
    public static final String VISIBLE="p.status='ON_SALE' AND s.status='ACTIVE' AND b.status='ACTIVE' AND c.status='ACTIVE' AND (pc.id IS NULL OR pc.status='ACTIVE')";
    public static final String JOIN=" FROM product p JOIN shop s ON s.id=p.shop_id JOIN book b ON b.id=p.book_id JOIN category c ON c.id=b.category_id LEFT JOIN category pc ON pc.id=c.parent_id JOIN sku k ON k.product_id=p.id JOIN inventory i ON i.sku_id=k.id";
    public static final String SELECT="SELECT p.id,p.shop_id,s.name shop_name,p.book_id,b.title,b.author,b.isbn,b.publisher,b.publication_date,b.category_id,b.description book_description,b.cover_file_id,b.status book_status,c.name category_name,c.status category_status,pc.status parent_status,s.status shop_status,k.id sku_id,k.sku_code,k.sale_price,k.version sku_version,p.status,p.featured,p.version,i.on_hand,i.reserved";
    private final SqlStore sql;
    public CatalogRepository(SqlStore sql) { this.sql=sql; }
    public Map<String,Object> product(String id) { return sql.required(SELECT+JOIN+" WHERE p.id=?",id); }
    public Map<String,Object> sku(String id) { return sql.required(SELECT+JOIN+" WHERE k.id=?",id); }
    public String coverStorageKey(Object file) {
        if(file==null) return null;
        return sql.one("SELECT storage_key FROM file_object WHERE id=? AND visibility='PUBLIC' AND purpose='BOOK_COVER' AND status='ACTIVE'",file).map(r->text(r,"storage_key")).orElse(null);
    }
    public Map<String,Object> lockProduct(String id) { return sql.required("SELECT * FROM product WHERE id=? FOR UPDATE",id); }
    public Map<String,Object> category(String id) { return sql.required("SELECT * FROM category WHERE id=?",id); }
    public Map<String,Object> book(String id) { return sql.required("SELECT b.*,c.name category_name FROM book b JOIN category c ON b.category_id=c.id WHERE b.id=?",id); }
    public List<Map<String,Object>> categories() { return sql.rows("SELECT * FROM category ORDER BY sort_order,id"); }
    public BookView bookView(Map<String,Object> row,boolean joined) {
        Object date=row.get("publication_date");
        LocalDate published=date==null ? null : date instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate)date;
        Object cover=row.get("cover_file_id");
        return new BookView(new ApiId(text(row,joined?"book_id":"id")),text(row,"isbn"),text(row,"title"),text(row,"author"),text(row,"publisher"),published,
                new ApiId(text(row,"category_id")),text(row,joined?"book_description":"description"),cover==null?null:new ApiId(cover.toString()),text(row,joined?"book_status":"status"),text(row,"category_name"),cover==null?null:"/api/v1/files/"+cover+"/content");
    }
    public ProductView view(Map<String,Object> row) {
        return new ProductView(new ApiId(text(row,"id")),new ApiId(text(row,"shop_id")),text(row,"shop_name"),bookView(row,true),new ApiId(text(row,"sku_id")),text(row,"sku_code"),new ApiMoney((BigDecimal)row.get("sale_price")),available(row),text(row,"status"),((Number)row.get("featured")).intValue()==1,number(row,"version"),number(row,"sku_version"));
    }
    public static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    public static long available(Map<String,Object> row) { return number(row,"on_hand")-number(row,"reserved"); }
    public static boolean visible(Map<String,Object> row) {
        return "ON_SALE".equals(row.get("status")) && "ACTIVE".equals(row.get("shop_status")) && "ACTIVE".equals(row.get("book_status")) && "ACTIVE".equals(row.get("category_status")) && (row.get("parent_status")==null || "ACTIVE".equals(row.get("parent_status")));
    }
    public record Filter(String shop,String status,String keyword,String category,Boolean featured,boolean publicOnly) {}
    private record Where(String sql,List<Object> args) {}
    private Where where(Filter filter) {
        var args=new ArrayList<Object>(); var clauses=new ArrayList<String>();
        clauses.add(filter.publicOnly()?VISIBLE:"1=1");
        if(filter.shop()!=null) {new ApiId(filter.shop()); clauses.add("p.shop_id=?");args.add(filter.shop());}
        if(filter.status()!=null) {clauses.add("p.status=?");args.add(filter.status());}
        if(filter.category()!=null) {new ApiId(filter.category());clauses.add("(c.id=? OR c.parent_id=?)");args.add(filter.category());args.add(filter.category());}
        if(filter.featured()!=null) {clauses.add("p.featured=?");args.add(filter.featured());}
        if(filter.keyword()!=null && !filter.keyword().isBlank()) {
            String keyword=SqlStore.like(filter.keyword());
            clauses.add("(b.isbn=? OR b.title LIKE ? ESCAPE '!' OR b.author LIKE ? ESCAPE '!')");
            args.add(filter.keyword().replace("-",""));args.add(keyword);args.add(keyword);
        }
        return new Where(" WHERE "+String.join(" AND ",clauses),args);
    }
    public PageData<ProductView> products(Filter filter,PageQuery page) {
        var where=where(filter); String order;
        if(page.sort()==null || page.sort().equals("NEWEST")) order="p.created_at DESC,p.id DESC";
        else if(page.sort().equals("PRICE_ASC")) order="k.sale_price ASC,p.id DESC";
        else if(page.sort().equals("PRICE_DESC")) order="k.sale_price DESC,p.id DESC";
        else throw new BusinessException(ErrorCode.PARAM_INVALID);
        // ISBN 精确项优先，所有排序片段均来自固定白名单，关键词只作为绑定参数。
        var args=new ArrayList<>(where.args());
        if(filter.keyword()!=null && !filter.keyword().isBlank()) {order="(b.isbn=?) DESC,"+order;args.add(filter.keyword().replace("-",""));}
        args.add(page.pageSize());args.add(page.offset());
        var items=sql.rows(SELECT+JOIN+where.sql()+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList();
        return new PageData<>(items,page.page(),page.pageSize(),sql.count("SELECT COUNT(*)"+JOIN+where.sql(),where.args().toArray()));
    }
    public PageData<BookView> books(PageQuery page,String keyword,boolean all) {
        String match=SqlStore.like(keyword); String where=" WHERE (b.isbn=? OR b.title LIKE ? ESCAPE '!' OR b.author LIKE ? ESCAPE '!')"+(all?"":" AND b.status='ACTIVE' AND c.status='ACTIVE' AND (pc.id IS NULL OR pc.status='ACTIVE')");
        Object exact=keyword==null?"":keyword.replace("-","");
        String joins=" FROM book b JOIN category c ON c.id=b.category_id LEFT JOIN category pc ON pc.id=c.parent_id";
        var list=sql.rows("SELECT b.*,c.name category_name"+joins+where+" ORDER BY (b.isbn=?) DESC,b.created_at DESC,b.id DESC LIMIT ? OFFSET ?",exact,match,match,exact,page.pageSize(),page.offset()).stream().map(row->bookView(row,false)).toList();
        return new PageData<>(list,page.page(),page.pageSize(),sql.count("SELECT COUNT(*)"+joins+where,exact,match,match));
    }
}
