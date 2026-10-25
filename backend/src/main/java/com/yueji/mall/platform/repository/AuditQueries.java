package com.yueji.mall.platform.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.PageQuery;
import com.yueji.mall.common.persistence.RowValues;
import com.yueji.mall.common.persistence.SqlStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class AuditQueries {
    public record Filter(String actorId,String action,String traceId,Instant from,Instant to) {}
    public record AuditView(ApiId id,ApiId actorId,String action,String resourceType,String resourceId,
            String result,JsonNode summary,String traceId,Instant createdAt) {}
    private static final String WHERE=" WHERE created_at>=? AND created_at<? AND (? IS NULL OR actor_id=?) AND (? IS NULL OR action=?) AND (? IS NULL OR trace_id=?) ";
    private final SqlStore sql;
    private final ObjectMapper mapper;
    public AuditQueries(SqlStore sql,ObjectMapper mapper) { this.sql=sql; this.mapper=mapper; }
    public List<AuditView> list(Filter filter,PageQuery page) {
        var params=new java.util.ArrayList<>(java.util.Arrays.asList(parameters(filter)));
        params.add(page.pageSize()); params.add(page.offset());
        return sql.rows("SELECT * FROM audit_log"+WHERE+"ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",params.toArray()).stream().map(this::view).toList();
    }
    public long count(Filter filter) { return sql.count("SELECT COUNT(*) FROM audit_log"+WHERE,parameters(filter)); }
    private Object[] parameters(Filter f) {
        return new Object[]{Timestamp.from(f.from()),Timestamp.from(f.to()),f.actorId(),f.actorId(),f.action(),f.action(),f.traceId(),f.traceId()};
    }
    private AuditView view(Map<String,Object> row) {
        try {
            return new AuditView(new ApiId(text(row,"id")),row.get("actor_id")==null ? null : new ApiId(text(row,"actor_id")),
                    text(row,"action"),text(row,"resource_type"),row.get("resource_id")==null ? null : text(row,"resource_id"),
                    text(row,"result"),mapper.readTree(text(row,"summary")),text(row,"trace_id"),RowValues.instant(row.get("created_at")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("审计摘要格式无效",error); }
    }
}
