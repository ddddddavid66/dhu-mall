package com.yueji.mall.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.identity.repository.BootstrapRepository;
import com.yueji.mall.identity.repository.IdentityRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevSeedService {
    private final BootstrapRepository repository;
    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final ObjectMapper mapper;
    private final Environment environment;
    public DevSeedService(BootstrapRepository repository,IdentityRepository identities,PasswordEncoder passwords,ObjectMapper mapper,Environment environment) {
        this.repository=repository; this.identities=identities; this.passwords=passwords; this.mapper=mapper; this.environment=environment;
    }
    @Transactional(rollbackFor=IOException.class)
    public boolean seed(Path credentialsFile) throws IOException {
        if (!environment.acceptsProfiles(Profiles.of("dev")) || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("开发种子只能在 dev 且没有 prod 的环境显式运行");
        }
        identities.lockAdminAnchor();
        if (repository.userCount()>0) return false;
        var credentials=new LinkedHashMap<String,Map<String,String>>();
        create(credentials,"admin_a","ADMIN"); create(credentials,"admin_b","ADMIN");
        create(credentials,"reader_a","MEMBER"); create(credentials,"reader_b","MEMBER");
        create(credentials,"operator_a","OPERATOR"); create(credentials,"support_a","SUPPORT");
        create(credentials,"finance_a","FINANCE"); create(credentials,"finance_b","FINANCE");
        for (int i=0;i<3;i++) {
            String suffix=String.valueOf((char)('a'+i));
            String shop=repository.createShop("虚构演示书店 "+suffix,i+1);
            repository.member(shop,create(credentials,"merchant_"+suffix,"MERCHANT"),"MERCHANT");
            repository.member(shop,create(credentials,"warehouse_"+suffix,"WAREHOUSE"),"WAREHOUSE");
        }
        Files.createDirectories(credentialsFile.toAbsolutePath().getParent());
        // CREATE_NEW 防止覆盖负责人已有凭据；不向日志或命令行输出任何口令。
        Files.writeString(credentialsFile,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(credentials),StandardOpenOption.CREATE_NEW);
        return true;
    }
    private String create(Map<String,Map<String,String>> credentials,String username,String role) {
        String password=Crypto.randomToken();
        String id=repository.createUser(username,"虚构演示 "+username,passwords.encode(password),role);
        credentials.put(username,Map.of("password",password,"role",role,"userId",id));
        return id;
    }
}
