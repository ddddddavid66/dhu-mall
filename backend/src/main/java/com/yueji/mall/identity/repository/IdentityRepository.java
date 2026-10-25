package com.yueji.mall.identity.repository;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.IdentityViews.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class IdentityRepository {
    private final SqlStore sql;
    public IdentityRepository(SqlStore sql) { this.sql = sql; }
    public Map<String,Object> user(String id) { return sql.required("SELECT * FROM iam_user WHERE id=?", id); }
    public Map<String,Object> lockUser(String id) { return sql.required("SELECT * FROM iam_user WHERE id=? FOR UPDATE", id); }
    public Optional<Map<String,Object>> byUsername(String username) {
        return sql.one("SELECT * FROM iam_user WHERE username=?", username);
    }
    public List<String> permissions(String id) {
        return sql.rows("""
                SELECT DISTINCT p.code FROM iam_permission p JOIN iam_role_permission rp ON p.id=rp.permission_id
                JOIN iam_role r ON rp.role_id=r.id AND r.status='ACTIVE'
                JOIN iam_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY p.code
                """, id).stream().map(row -> text(row,"code")).toList();
    }
    public UserView view(String id) {
        var user = user(id);
        var roles = sql.rows("""
                SELECT r.code FROM iam_role r JOIN iam_user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=? AND r.status='ACTIVE' ORDER BY r.code
                """, id).stream().map(row -> text(row,"code")).toList();
        var grouped = new LinkedHashMap<String,List<Map<String,Object>>>();
        for (var member : sql.rows("""
                SELECT s.id,s.name,m.duty FROM shop_member m JOIN shop s ON s.id=m.shop_id
                WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY s.id,m.duty
                """, id)) grouped.computeIfAbsent(text(member,"id"), key -> new ArrayList<>()).add(member);
        var shops = grouped.entrySet().stream().map(entry -> new ShopScope(new ApiId(entry.getKey()),
                text(entry.getValue().getFirst(),"name"), entry.getValue().stream().map(row -> text(row,"duty")).toList())).toList();
        return new UserView(new ApiId(id),text(user,"username"),text(user,"nickname"),text(user,"status"),roles,permissions(id),shops);
    }
    public void revokeUser(String id) {
        sql.update("UPDATE iam_user SET auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);
        sql.update("UPDATE refresh_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE user_id=?",id);
    }
    public long authVersion(Map<String,Object> row) { return ((Number)row.get("auth_version")).longValue(); }
    public Map<String,Object> memberRole() { return sql.required("SELECT id,status FROM iam_role WHERE code='MEMBER' FOR UPDATE"); }
    public String createUser(String username,String hash,String nickname) {
        return sql.insert("INSERT INTO iam_user(username,password_hash,nickname) VALUES(?,?,?)",username,hash,nickname);
    }
    public void assignRole(String user,Object role) { sql.insert("INSERT INTO iam_user_role(user_id,role_id) VALUES(?,?)",user,role); }
    public void setPassword(String user,String hash) { sql.update("UPDATE iam_user SET password_hash=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",hash,user); }
    public boolean hasLiveFamily(String user,String family,long version) {
        return sql.count("SELECT COUNT(*) FROM refresh_session WHERE user_id=? AND family_no=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3) AND auth_version=?",user,family,version)>0;
    }
    public boolean hasShopMembership(String user,String shop,String duty) {
        return sql.count("SELECT COUNT(*) FROM shop_member WHERE user_id=? AND shop_id=? AND duty=? AND status='ACTIVE'",user,shop,duty)>0;
    }
    public void lockAdminAnchor() { sql.required("SELECT id FROM iam_role WHERE code='ADMIN' FOR UPDATE"); }
    // 多个买家共享读锁，商品/分类/店铺/授权变更使用已有写锁；避免核验后被并发下架或变价。
    public void shareCommerceAnchor() {sql.required("SELECT id FROM iam_role WHERE code='ADMIN' FOR SHARE");}
    public long effectiveAdministrators() {
        return sql.count("""
                SELECT COUNT(DISTINCT u.id) FROM iam_user u JOIN iam_user_role ur ON u.id=ur.user_id
                JOIN iam_role r ON r.id=ur.role_id AND r.code='ADMIN' AND r.status='ACTIVE'
                WHERE u.status='ACTIVE' AND EXISTS(SELECT 1 FROM iam_role_permission rp JOIN iam_permission p ON p.id=rp.permission_id
                WHERE rp.role_id=r.id AND p.code='iam.user.manage') AND EXISTS(SELECT 1 FROM iam_role_permission rp
                JOIN iam_permission p ON p.id=rp.permission_id WHERE rp.role_id=r.id AND p.code='iam.role.manage')
                """);
    }
}
