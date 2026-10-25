package com.yueji.mall.identity.service;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name="yueji.dev-seed.enabled",havingValue="true")
public class DevSeedRunner implements ApplicationRunner {
    private final DevSeedService service;
    private final String path;
    public DevSeedRunner(DevSeedService service,@Value("${yueji.dev-seed.credentials-file}") String path) { this.service=service; this.path=path; }
    public void run(ApplicationArguments args) throws Exception {
        if (path.isBlank()) throw new IllegalStateException("显式启用种子时必须指定 DEV_SEED_CREDENTIALS_FILE，本地凭据不能打印到日志");
        service.seed(Path.of(path));
    }
}
