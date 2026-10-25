package com.yueji.mall.identity.repository;

import com.yueji.mall.common.persistence.SqlStore;
import org.springframework.stereotype.Repository;

@Repository
public class BootstrapRepository {
    private final SqlStore sql;
    private final IdentityRepository identities;
    public BootstrapRepository(SqlStore sql,IdentityRepository identities) { this.sql=sql; this.identities=identities; }
    public long userCount() { return sql.count("SELECT COUNT(*) FROM iam_user"); }
    public String createUser(String username,String nickname,String passwordHash,String role) {
        String user=identities.createUser(username,passwordHash,nickname);
        Object roleId=sql.required("SELECT id FROM iam_role WHERE code=? AND status='ACTIVE'",role).get("id");
        identities.assignRole(user,roleId);
        return user;
    }
    public String createShop(String name,int number) {
        return sql.insert("INSERT INTO shop(name,contact_name,contact_phone) VALUES(?,?,?)",name,"演示联系人","1380000000"+number);
    }
    public void member(String shop,String user,String duty) { sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,?)",shop,user,duty); }
}
