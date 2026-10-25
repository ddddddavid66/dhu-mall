package com.yueji.mall.identity.model;

import com.yueji.mall.common.api.ApiId;
import java.time.Instant;
import java.util.List;

public final class IdentityViews {
    private IdentityViews() {}
    public record ShopScope(ApiId id, String name, List<String> duties) {}
    public record UserView(ApiId id, String username, String nickname, String status,
            List<String> roles, List<String> permissions, List<ShopScope> shops) {}
    public record AuthView(String accessToken, long expiresIn, String csrfToken, UserView user) {}
    public record PermissionView(ApiId id, String code, String name) {}
    public record RoleView(ApiId id, String code, String name, String status, List<ApiId> permissionIds) {}
    public record ShopView(ApiId id, String name, String contactName, String contactPhone, String status, Instant createdAt) {}
    public record MemberView(ApiId id, ApiId shopId, ApiId userId, String username, String duty, String status) {}
}
