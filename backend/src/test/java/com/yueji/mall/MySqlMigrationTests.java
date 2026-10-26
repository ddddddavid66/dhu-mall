package com.yueji.mall;

import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MySqlMigrationTests extends MySqlTestSupport {
    private DriverManagerDataSource source() {
        String url=databaseUrl().replace("/yueji_mall_test","/yueji_migration_test");
        return new DriverManagerDataSource(url,System.getenv("YUEJI_TEST_DB_USERNAME"),System.getenv("YUEJI_TEST_DB_PASSWORD"));
    }
    private Flyway flyway(String target) {
        var config=Flyway.configure().dataSource(source()).locations("classpath:db/migration").cleanDisabled(false);
        if (target!=null) config.target(MigrationVersion.fromVersion(target));
        return config.load();
    }
    @Test void emptyDatabaseRepeatStartupAndForwardCompatibleMigrationPreserveLegacyRows() {
        var migrations=flyway(null); migrations.clean();
        var first=flyway("1");
        assertThat(first.migrate().migrationsExecuted).isEqualTo(1);
        var jdbc=new JdbcTemplate(source());
        jdbc.update("INSERT INTO iam_user(username,password_hash,nickname) VALUES('legacy_user','not-a-production-password','旧版测试行')");
        assertThat(migrations.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(migrations.migrate().migrationsExecuted).isZero();
        // 使用 V1 的列和查询方式验证向前兼容，不伪造不存在的数据库降级。
        assertThat(jdbc.queryForObject("SELECT nickname FROM iam_user WHERE username='legacy_user'",String.class)).isEqualTo("旧版测试行");
        assertThat(jdbc.queryForObject("SELECT VERSION()",String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_user",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_permission",Integer.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='reason_type'",Integer.class)).isEqualTo(32);
        for(String table:List.of("shipment","shipment_item","shipment_event","settlement","settlement_item","settlement_adjustment","consumed_event"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table)).isEqualTo(1);
    }
    @Test void mysqlConstraintsAndAuditImmutabilityAreEnforcedByTheDatabase() {
        var migrations=flyway(null); migrations.clean(); migrations.migrate();
        var jdbc=new JdbcTemplate(source());
        jdbc.update("INSERT INTO iam_user(username,password_hash,nickname) VALUES('constraint_user','test-hash','虚构测试')");
        long id=jdbc.queryForObject("SELECT id FROM iam_user WHERE username='constraint_user'",Long.class);
        String insert="INSERT INTO user_address(user_id,receiver_name,phone,province,city,district,detail,is_default) VALUES(?,'测试','13800000001','测试','测试','测试','虚构地址',1)";
        jdbc.update(insert,id);
        assertThatThrownBy(()->jdbc.update(insert,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO iam_user_role(user_id,role_id) VALUES(?,999999)",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM iam_user WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE iam_user SET status='INVALID' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("INSERT INTO audit_log(actor_id,action,resource_type,result,summary,trace_id) VALUES(?,'test','user','SUCCESS',JSON_OBJECT(),'migration-test')",id);
        assertThatThrownBy(()->jdbc.update("UPDATE audit_log SET action='tamper'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM audit_log")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isEqualTo(1);
        var expected=List.of("iam_user","iam_role","iam_permission","iam_user_role","iam_role_permission","shop","shop_member","refresh_session","user_address","audit_log");
        for(String table:expected) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ('id','created_at','updated_at')",Integer.class,table)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='iam_user' AND column_name='id'",String.class)).isEqualTo("bigint unsigned");
    }
}
