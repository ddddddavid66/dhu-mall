package com.yueji.mall.identity.repository;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.api.PageQuery;
import com.yueji.mall.common.persistence.RowValues;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.IdentityInputs.*;
import com.yueji.mall.identity.model.IdentityViews.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class AdministrationRepository {
    private final SqlStore sql;
    public AdministrationRepository(SqlStore sql) { this.sql=sql; }
    public List<String> userIds(PageQuery page,String keyword,Status status) {
        return sql.rows("""
                SELECT id FROM iam_user WHERE (username LIKE ? ESCAPE '!' OR nickname LIKE ? ESCAPE '!')
                AND (? IS NULL OR status=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """,SqlStore.like(keyword),SqlStore.like(keyword),name(status),name(status),page.pageSize(),page.offset()).stream().map(row->text(row,"id")).toList();
    }
    public long userCount(String keyword,Status status) {
        return sql.count("SELECT COUNT(*) FROM iam_user WHERE (username LIKE ? ESCAPE '!' OR nickname LIKE ? ESCAPE '!') AND (? IS NULL OR status=?)",
                SqlStore.like(keyword),SqlStore.like(keyword),name(status),name(status));
    }
    public String createUser(String username,String nickname,String hash) {
        return sql.insert("INSERT INTO iam_user(username,nickname,password_hash) VALUES(?,?,?)",username,nickname,hash);
    }
    public void editUser(String id,UserEdit input) {
        sql.update("UPDATE iam_user SET nickname=?,status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",input.nickname(),input.status().name(),id);
    }
    public void replaceRoles(String user,List<ApiId> roles) {
        sql.update("DELETE FROM iam_user_role WHERE user_id=?",user);
        for (var id:roles) sql.insert("INSERT INTO iam_user_role(user_id,role_id) VALUES(?,?)",user,id.value());
    }
    public void validateReferences(String table,List<ApiId> ids) {
        if (!Set.of("iam_role","iam_permission").contains(table)) throw new IllegalArgumentException("未授权的关系表");
        if (ids.size()!=ids.stream().distinct().count()) throw new BusinessException(ErrorCode.PARAM_INVALID);
        if (ids.isEmpty()) return;
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        long existing=sql.count("SELECT COUNT(*) FROM "+table+" WHERE id IN ("+placeholders+")",ids.stream().map(ApiId::value).toArray());
        if (existing!=ids.size()) throw new BusinessException(ErrorCode.PARAM_INVALID);
    }
    public RoleView role(String id) {
        var row=sql.required("SELECT * FROM iam_role WHERE id=?",id);
        var permissions=sql.rows("SELECT permission_id FROM iam_role_permission WHERE role_id=? ORDER BY permission_id",id)
                .stream().map(p->new ApiId(text(p,"permission_id"))).toList();
        return new RoleView(new ApiId(id),text(row,"code"),text(row,"name"),text(row,"status"),permissions);
    }
    public List<RoleView> roles(PageQuery page) {
        return sql.rows("SELECT id FROM iam_role ORDER BY id LIMIT ? OFFSET ?",page.pageSize(),page.offset()).stream().map(row->role(text(row,"id"))).toList();
    }
    public long roleCount() { return sql.count("SELECT COUNT(*) FROM iam_role"); }
    public String createRole(RoleCreate input) { return sql.insert("INSERT INTO iam_role(code,name) VALUES(?,?)",input.code(),input.name()); }
    public void editRole(String id,RoleEdit input) {
        sql.required("SELECT id FROM iam_role WHERE id=? FOR UPDATE",id);
        sql.update("UPDATE iam_role SET name=?,status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",input.name(),input.status().name(),id);
    }
    public void replacePermissions(String id,List<ApiId> permissions) {
        sql.update("DELETE FROM iam_role_permission WHERE role_id=?",id);
        for (var permission:permissions) sql.insert("INSERT INTO iam_role_permission(role_id,permission_id) VALUES(?,?)",id,permission.value());
    }
    public List<String> usersWithRole(String id) {
        return sql.rows("SELECT user_id FROM iam_user_role WHERE role_id=? ORDER BY user_id",id).stream().map(row->text(row,"user_id")).toList();
    }
    public List<ShopView> shops(PageQuery page,ShopStatus status) {
        return sql.rows("SELECT * FROM shop WHERE (? IS NULL OR status=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                name(status),name(status),page.pageSize(),page.offset()).stream().map(this::shop).toList();
    }
    public long shopCount(ShopStatus status) { return sql.count("SELECT COUNT(*) FROM shop WHERE (? IS NULL OR status=?)",name(status),name(status)); }
    public ShopView shop(String id) { return shop(sql.required("SELECT * FROM shop WHERE id=?",id)); }
    public String createShop(ShopInput input) {
        return sql.insert("INSERT INTO shop(name,status,contact_name,contact_phone) VALUES(?,?,?,?)",
                input.name(),input.status().name(),input.contactName(),input.contactPhone());
    }
    public void editShop(String id,ShopInput input) {
        sql.required("SELECT id FROM shop WHERE id=? FOR UPDATE",id);
        sql.update("UPDATE shop SET name=?,status=?,contact_name=?,contact_phone=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                input.name(),input.status().name(),input.contactName(),input.contactPhone(),id);
    }
    public List<MemberView> members(String shop,PageQuery page) {
        shop(shop);
        return sql.rows("SELECT m.*,u.username FROM shop_member m JOIN iam_user u ON u.id=m.user_id WHERE m.shop_id=? ORDER BY m.id LIMIT ? OFFSET ?",
                shop,page.pageSize(),page.offset()).stream().map(this::member).toList();
    }
    public long memberCount(String shop) { return sql.count("SELECT COUNT(*) FROM shop_member WHERE shop_id=?",shop); }
    public boolean hasMember(String shop,String user,Duty duty) { return sql.count("SELECT COUNT(*) FROM shop_member WHERE shop_id=? AND user_id=? AND duty=?",shop,user,duty.name())>0; }
    public MemberView upsertMember(String shop,String user,Duty duty,Status status) {
        sql.update("""
                INSERT INTO shop_member(shop_id,user_id,duty,status) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE status=VALUES(status),updated_at=UTC_TIMESTAMP(3)
                """,shop,user,duty.name(),status.name());
        return member(sql.required("SELECT m.*,u.username FROM shop_member m JOIN iam_user u ON u.id=m.user_id WHERE m.shop_id=? AND m.user_id=? AND m.duty=?",shop,user,duty.name()));
    }
    private ShopView shop(Map<String,Object> row) {
        String phone=text(row,"contact_phone");
        String masked=phone.length()<5 ? "***" : phone.substring(0,3)+"****"+phone.substring(phone.length()-2);
        return new ShopView(new ApiId(text(row,"id")),text(row,"name"),text(row,"contact_name"),masked,text(row,"status"),RowValues.instant(row.get("created_at")));
    }
    private MemberView member(Map<String,Object> row) {
        return new MemberView(new ApiId(text(row,"id")),new ApiId(text(row,"shop_id")),new ApiId(text(row,"user_id")),text(row,"username"),text(row,"duty"),text(row,"status"));
    }
    private static String name(Enum<?> value) { return value==null ? null : value.name(); }
}
