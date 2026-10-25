package com.yueji.mall.platform.repository;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.common.web.TraceIdFilter;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Repository
public class AuditRepository {
    private final SqlStore sql;
    public AuditRepository(SqlStore sql) { this.sql = sql; }
    public void append(String actorId, String action, String type, String id, String shopId, String result) {
        appendReason(actorId,action,type,id,shopId,result,null);
    }
    public void appendReason(String actorId,String action,String type,String id,String shopId,String result,String reason) {
        var attributes = RequestContextHolder.getRequestAttributes();
        String trace = attributes instanceof ServletRequestAttributes servlet
                ? TraceIdFilter.current(servlet.getRequest()) : java.util.UUID.randomUUID().toString();
        // 仅记录动作及显式业务原因，不写原始请求体、口令、令牌、姓名和手机号。
        sql.insert("""
                INSERT INTO audit_log(actor_id,action,resource_type,resource_id,shop_id,result,summary,trace_id)
                VALUES(?,?,?,?,?,?,IF(? IS NULL,JSON_OBJECT(),JSON_OBJECT('reason',?)),?)
                """,actorId,action,type,id,shopId,result,reason,reason,trace);
    }
}
