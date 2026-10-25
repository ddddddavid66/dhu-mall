package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.model.IdentityInputs.*;
import com.yueji.mall.identity.model.IdentityViews.UserView;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.platform.repository.AuditRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class AccountService {
    private final IdentityRepository identities;
    private final IdentityPolicy policy;
    private final PasswordEncoder passwords;
    private final AuditRepository audit;
    public AccountService(IdentityRepository identities,IdentityPolicy policy,PasswordEncoder passwords,AuditRepository audit) {
        this.identities=identities; this.policy=policy; this.passwords=passwords; this.audit=audit;
    }
    @Transactional
    public UserView register(RegisterInput input) {
        var role=identities.memberRole();
        if (!"ACTIVE".equals(text(role,"status"))) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        String id=identities.createUser(input.username(),passwords.encode(input.password()),input.nickname());
        identities.assignRole(id,role.get("id"));
        audit.append(id,"auth.register","user",id,null,"SUCCESS");
        return identities.view(id);
    }
    @Transactional(readOnly=true)
    public UserView me(Actor actor) { policy.current(actor); return identities.view(actor.userId()); }
    @Transactional
    public void changePassword(Actor actor,PasswordInput input) {
        var user=identities.lockUser(actor.userId());
        policy.current(actor);
        if (!passwords.matches(input.oldPassword(),text(user,"password_hash"))) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        identities.setPassword(actor.userId(),passwords.encode(input.newPassword()));
        identities.revokeUser(actor.userId());
        audit.append(actor.userId(),"auth.change_password","user",actor.userId(),null,"SUCCESS");
    }
}
