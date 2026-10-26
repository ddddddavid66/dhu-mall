package com.yueji.mall;

import com.yueji.mall.identity.service.Crypto;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@org.springframework.context.annotation.Import(ContractObservationConfiguration.class)
public abstract class MySqlTestSupport {
    private static final String KEY=Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(Crypto.randomToken()));
    public static String databaseUrl() {
        String url=System.getenv("YUEJI_TEST_DB_URL");
        if (url==null || !url.matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/yueji_mall_test(?:\\?.*)?$")) {
            throw new IllegalStateException("测试仅允许明确配置的本机 yueji_mall_test，不使用 H2，也不自动触碰其他库");
        }
        return url;
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MySqlTestSupport::databaseUrl);
        properties.add("spring.datasource.username",() -> required("YUEJI_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password",() -> required("YUEJI_TEST_DB_PASSWORD"));
        properties.add("yueji.security.jwt-key",() -> KEY);
        properties.add("yueji.security.login-limit",() -> 1000);
        properties.add("yueji.security.write-limit",() -> 1000);
        properties.add("yueji.security.public-read-limit",() -> 1000);
        properties.add("yueji.trade.expiry-enabled",() -> false);
        properties.add("yueji.messaging.scheduling-enabled",() -> false);
        properties.add("yueji.scheduling.enabled",() -> false);
        properties.add("yueji.files.root",() -> "../.artifacts/storage/test");
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalStateException("缺少隔离测试环境变量 "+name);
        return value;
    }
}
