package com.yueji.mall;

import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.identity.service.InitialAdministratorService;
import com.yueji.mall.common.persistence.SqlStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"management.server.port=0","logging.level.root=WARN"})
class InitialAdministratorTests extends MySqlTestSupport {
    @Autowired Flyway migrations;
    @Autowired InitialAdministratorService bootstrap;
    @Autowired SqlStore sql;
    @Autowired PasswordEncoder passwords;
    @Test void firstAdministratorNeedsExplicitCredentialAndEmptyDatabaseAndCannotBeRecreated() {
        Flyway.configure().dataSource(migrations.getConfiguration().getDataSource()).locations("classpath:db/migration").cleanDisabled(false).load().clean(); migrations.migrate();
        assertThatThrownBy(()->bootstrap.bootstrap("initial_admin","")).isInstanceOf(IllegalStateException.class);
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user")).isZero();
        String password=Crypto.randomToken(); bootstrap.bootstrap("initial_admin",password);
        String hash=sql.required("SELECT password_hash FROM iam_user WHERE username='initial_admin'").get("password_hash").toString();
        assertThat(passwords.matches(password,hash)).isTrue();
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id WHERE r.code='ADMIN'")).isEqualTo(1);
        assertThatThrownBy(()->bootstrap.bootstrap("initial_other",Crypto.randomToken())).isInstanceOf(IllegalStateException.class);
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user")).isEqualTo(1);
    }
}
