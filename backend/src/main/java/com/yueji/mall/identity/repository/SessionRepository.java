package com.yueji.mall.identity.repository;

import com.yueji.mall.common.persistence.SqlStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final SqlStore sql;
    public SessionRepository(SqlStore sql) { this.sql=sql; }
    public Map<String,Object> byHash(String hash) { return sql.one("SELECT * FROM refresh_session WHERE token_hash=?",hash).orElse(null); }
    public Map<String,Object> lock(Object id) { return sql.required("SELECT * FROM refresh_session WHERE id=? FOR UPDATE",id); }
    public void loginFailure(String user,int failures,Instant lockUntil) {
        sql.update("UPDATE iam_user SET failed_login_count=?,locked_until=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",failures,lockUntil==null ? null : Timestamp.from(lockUntil),user);
    }
    public void loginSuccess(String user) {
        sql.update("UPDATE iam_user SET failed_login_count=0,locked_until=NULL,last_login_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=?",user);
    }
    public String create(Object user,String family,String hash,Instant expiry,String csrfHash,long version) {
        return sql.insert("INSERT INTO refresh_session(user_id,family_no,token_hash,expires_at,csrf_hash,auth_version) VALUES(?,?,?,?,?,?)",
                user,family,hash,Timestamp.from(expiry),csrfHash,version);
    }
    public void replace(Object oldId,String newId) {
        sql.update("UPDATE refresh_session SET revoked_at=UTC_TIMESTAMP(3),replaced_by_id=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",newId,oldId);
    }
    public void revokeFamily(String family) {
        sql.update("UPDATE refresh_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE family_no=?",family);
    }
}
