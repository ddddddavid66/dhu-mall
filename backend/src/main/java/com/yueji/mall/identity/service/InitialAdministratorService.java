package com.yueji.mall.identity.service;

import com.yueji.mall.identity.model.IdentityInputs.RegisterInput;
import com.yueji.mall.identity.repository.BootstrapRepository;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.platform.repository.AuditRepository;
import jakarta.validation.Validator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdministratorService {
    private final Environment environment;
    private final Validator validator;
    private final IdentityRepository identities;
    private final BootstrapRepository repository;
    private final PasswordEncoder passwords;
    private final AuditRepository audit;
    public InitialAdministratorService(Environment environment,Validator validator,IdentityRepository identities,
            BootstrapRepository repository,PasswordEncoder passwords,AuditRepository audit) {
        this.environment=environment; this.validator=validator; this.identities=identities;
        this.repository=repository; this.passwords=passwords; this.audit=audit;
    }
    @Transactional
    public void bootstrap(String username,String password) {
        if (!environment.acceptsProfiles(Profiles.of("prod")) || environment.acceptsProfiles(Profiles.of("dev"))) throw new IllegalStateException("初始化管理员入口仅用于显式 prod 初始化");
        if (!validator.validate(new RegisterInput(username,password,"初始化管理员")).isEmpty()) throw new IllegalStateException("必须通过环境变量提供合法用户名和强口令");
        identities.lockAdminAnchor();
        if (repository.userCount()!=0) throw new IllegalStateException("已有账号时拒绝再次初始化管理员");
        String id=repository.createUser(username,"初始化管理员",passwords.encode(password),"ADMIN");
        audit.append(id,"iam.bootstrap_admin","user",id,null,"SUCCESS");
    }
}
