package com.yueji.mall.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod & !dev")
@ConditionalOnProperty(name="yueji.bootstrap-admin.enabled",havingValue="true")
public class InitialAdministratorRunner implements ApplicationRunner {
    private final InitialAdministratorService service;
    private final String username,password;
    public InitialAdministratorRunner(InitialAdministratorService service,@Value("${INITIAL_ADMIN_USERNAME:}") String username,
            @Value("${INITIAL_ADMIN_PASSWORD:}") String password) { this.service=service; this.username=username; this.password=password; }
    public void run(ApplicationArguments args) { service.bootstrap(username,password); }
}
