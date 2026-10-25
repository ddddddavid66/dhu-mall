package com.yueji.mall.identity.model;

import com.yueji.mall.common.api.ApiId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class IdentityInputs {
    private IdentityInputs() {}
    public enum Status { ACTIVE, DISABLED }
    public enum ShopStatus { ACTIVE, SUSPENDED }
    public enum Duty { MERCHANT, WAREHOUSE }
    public record RegisterInput(@NotNull @Pattern(regexp="[a-z0-9_]{4,32}") String username,
            @ValidPassword String password, @NotBlank @Size(max=40) String nickname) {}
    public record LoginInput(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password) {}
    public record PasswordInput(@NotBlank @Size(max=72) String oldPassword, @ValidPassword String newPassword) {}
    public record UserCreate(@NotNull @Pattern(regexp="[a-z0-9_]{4,32}") String username,
            @NotBlank @Size(max=40) String nickname, @ValidPassword String initialPassword,
            @NotNull @Size(max=100) List<@NotNull @Valid ApiId> roleIds) {}
    public record UserEdit(@NotBlank @Size(max=40) String nickname, @NotNull Status status,
            @NotNull @Size(max=100) List<@NotNull @Valid ApiId> roleIds) {}
    public record RoleCreate(@NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{2,31}") String code,
            @NotBlank @Size(max=50) String name, @NotNull @Size(max=100) List<@NotNull @Valid ApiId> permissionIds) {}
    public record RoleEdit(@NotBlank @Size(max=50) String name, @NotNull Status status,
            @NotNull @Size(max=100) List<@NotNull @Valid ApiId> permissionIds) {}
    public record ShopInput(@NotBlank @Size(max=80) String name, @NotBlank @Size(max=40) String contactName,
            @NotBlank @Size(max=32) String contactPhone, @NotNull ShopStatus status) {}
    public record MemberInput(@NotNull Status status) {}
}
