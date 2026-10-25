package com.yueji.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.service.DevSeedService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="yueji.dev-seed.enabled=false")
class SeedIsolationTests extends MySqlTestSupport {
    @Autowired Flyway migrations;
    @Autowired DevSeedService seeds;
    @Autowired SqlStore sql;
    @Autowired PasswordEncoder passwords;
    @Test void explicitDevelopmentSeedsAreRandomIdempotentAndContainSeparateFinancialOperators() throws Exception {
        Flyway.configure().dataSource(migrations.getConfiguration().getDataSource()).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        migrations.migrate();
        Path path=Path.of("../.artifacts/B-seed-test-"+UUID.randomUUID()+".json");
        assertThat(seeds.seed(path)).isTrue();
        var credentials=new ObjectMapper().readTree(Files.readString(path));
        assertThat(credentials.size()).isEqualTo(14);
        String first=credentials.path("finance_a").path("password").asText();
        String second=credentials.path("finance_b").path("password").asText();
        assertThat(first.equals(second)).isFalse();
        String stored=sql.required("SELECT password_hash FROM iam_user WHERE username='finance_a'").get("password_hash").toString();
        assertThat(passwords.matches(first,stored)).isTrue();
        assertThat(sql.count("SELECT COUNT(*) FROM shop")).isEqualTo(3);
        assertThat(sql.count("SELECT COUNT(*) FROM shop_member")).isEqualTo(6);
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session")).isZero();
        assertThat(seeds.seed(path)).isFalse();
        var arguments=new org.springframework.boot.DefaultApplicationArguments(new String[0]);
        assertThatThrownBy(()->new com.yueji.mall.identity.service.DevSeedRunner(seeds,"").run(arguments)).isInstanceOf(IllegalStateException.class);
        new com.yueji.mall.identity.service.DevSeedRunner(seeds,path.toString()).run(arguments);
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user")).isEqualTo(14);
        assertThat(new ObjectMapper().readTree(Files.readString(path)).path("finance_a").path("password").asText().equals(first)).isTrue();
    }
}
