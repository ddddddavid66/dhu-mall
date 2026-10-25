package com.yueji.mall.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.PageData;
import com.yueji.mall.common.api.PageQuery;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.model.IdentityInputs.*;
import com.yueji.mall.identity.model.IdentityViews.*;
import com.yueji.mall.identity.repository.*;
import com.yueji.mall.platform.repository.AuditRepository;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {
    private final AdministrationRepository repository;
    private final IdentityRepository identities;
    private final IdentityPolicy policy;
    private final PasswordEncoder passwords;
    private final PermissionMapper permissions;
    private final AuditRepository audit;
    public AdministrationService(AdministrationRepository repository,IdentityRepository identities,IdentityPolicy policy,
            PasswordEncoder passwords,PermissionMapper permissions,AuditRepository audit) {
        this.repository=repository; this.identities=identities; this.policy=policy;
        this.passwords=passwords; this.permissions=permissions; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public PageData<UserView> users(Actor actor,PageQuery page,String keyword,Status status) {
        policy.require(actor,"iam.user.manage"); validPage(page);
        return new PageData<>(repository.userIds(page,keyword,status).stream().map(identities::view).toList(),page.page(),page.pageSize(),repository.userCount(keyword,status));
    }
    @Transactional
    public UserView createUser(Actor actor,UserCreate input) {
        policy.lockAdminChanges(actor,"iam.user.manage");
        repository.validateReferences("iam_role",input.roleIds());
        String id=repository.createUser(input.username(),input.nickname(),passwords.encode(input.initialPassword()));
        repository.replaceRoles(id,input.roleIds());
        audit.append(actor.userId(),"iam.user.create","user",id,null,"SUCCESS");
        return identities.view(id);
    }
    @Transactional
    public UserView editUser(Actor actor,String id,UserEdit input) {
        new ApiId(id); policy.lockAdminChanges(actor,"iam.user.manage"); identities.lockUser(id);
        repository.validateReferences("iam_role",input.roleIds());
        repository.editUser(id,input); repository.replaceRoles(id,input.roleIds());
        policy.ensureLastAdministrator(); identities.revokeUser(id);
        audit.append(actor.userId(),"iam.user.update","user",id,null,"SUCCESS");
        return identities.view(id);
    }
    @Transactional(readOnly=true)
    public PageData<RoleView> roles(Actor actor,PageQuery page) {
        policy.require(actor,"iam.role.manage"); validPage(page);
        return new PageData<>(repository.roles(page),page.page(),page.pageSize(),repository.roleCount());
    }
    @Transactional
    public RoleView createRole(Actor actor,RoleCreate input) {
        policy.lockAdminChanges(actor,"iam.role.manage"); repository.validateReferences("iam_permission",input.permissionIds());
        String id=repository.createRole(input); repository.replacePermissions(id,input.permissionIds());
        audit.append(actor.userId(),"iam.role.create","role",id,null,"SUCCESS"); return repository.role(id);
    }
    @Transactional
    public RoleView editRole(Actor actor,String id,RoleEdit input) {
        new ApiId(id); policy.lockAdminChanges(actor,"iam.role.manage"); repository.validateReferences("iam_permission",input.permissionIds());
        repository.editRole(id,input); repository.replacePermissions(id,input.permissionIds()); policy.ensureLastAdministrator();
        for (String user:repository.usersWithRole(id)) { identities.lockUser(user); identities.revokeUser(user); }
        audit.append(actor.userId(),"iam.role.update","role",id,null,"SUCCESS"); return repository.role(id);
    }
    public List<PermissionView> permissions(Actor actor) {
        policy.require(actor,"iam.role.manage");
        return permissions.selectList(new QueryWrapper<PermissionEntity>().orderByAsc("id")).stream()
                .map(p->new PermissionView(new ApiId(p.id),p.code,p.name)).toList();
    }
    @Transactional(readOnly=true)
    public PageData<ShopView> shops(Actor actor,PageQuery page,ShopStatus status) {
        policy.require(actor,"platform.shop.manage"); validPage(page);
        return new PageData<>(repository.shops(page,status),page.page(),page.pageSize(),repository.shopCount(status));
    }
    @Transactional
    public ShopView createShop(Actor actor,ShopInput input) {
        policy.lockAdminChanges(actor,"platform.shop.manage"); String id=repository.createShop(input);
        audit.append(actor.userId(),"shop.create","shop",id,id,"SUCCESS"); return repository.shop(id);
    }
    @Transactional
    public ShopView editShop(Actor actor,String id,ShopInput input) {
        new ApiId(id); policy.lockAdminChanges(actor,"platform.shop.manage"); repository.editShop(id,input);
        audit.append(actor.userId(),"shop.update","shop",id,id,"SUCCESS"); return repository.shop(id);
    }
    @Transactional(readOnly=true)
    public PageData<MemberView> members(Actor actor,String id,PageQuery page) {
        new ApiId(id); policy.require(actor,"platform.shop.manage"); validPage(page);
        return new PageData<>(repository.members(id,page),page.page(),page.pageSize(),repository.memberCount(id));
    }
    public record MemberResult(MemberView member,boolean created) {}
    @Transactional
    public MemberResult setMember(Actor actor,String shop,String user,Duty duty,MemberInput input) {
        new ApiId(shop); new ApiId(user); policy.lockAdminChanges(actor,"platform.shop.manage");
        repository.shop(shop); identities.lockUser(user);
        boolean created=!repository.hasMember(shop,user,duty);
        var member=repository.upsertMember(shop,user,duty,input.status()); identities.revokeUser(user);
        audit.append(actor.userId(),"shop.member.update","member",member.id().value(),shop,"SUCCESS"); return new MemberResult(member,created);
    }
    private void validPage(PageQuery page) {
        if (page.sort()!=null) throw new com.yueji.mall.common.api.BusinessException(com.yueji.mall.common.api.ErrorCode.PARAM_INVALID);
    }
}
