package com.yueji.mall.common.persistence;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class SqlStore {
    private final JdbcTemplate jdbc;
    public SqlStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String, Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }
    public Optional<Map<String, Object>> one(String sql, Object... args) { return rows(sql, args).stream().findFirst(); }
    public Map<String, Object> required(String sql, Object... args) {
        return one(sql, args).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    public String insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        // MySQL unsigned BIGINT 可能由驱动返回 BigInteger，不能强转成 long。
        return keys.getKey().toString();
    }
    public static String text(Map<String, Object> row, String field) { return row.get(field).toString(); }
    public static String like(String input) {
        if (input == null) return "%";
        if (input.length() > 100) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return "%" + input.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
