package com.dailymoney.permission;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.shared.security.FirebasePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** LLD §11 permission endpoints. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class PermissionController {

    private final PermissionAdminService admin;
    private final CurrentUserService currentUser;

    public PermissionController(PermissionAdminService admin, CurrentUserService currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    @GetMapping("/permissions")
    public PermissionMatrixDto matrix(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId) {
        PermissionAdminService.RoleMatrix m = admin.matrix(me(jwt), orgId);
        boolean canManage = m.access().has(Permission.PERMISSION_MANAGE);
        return new PermissionMatrixDto(
                Arrays.stream(Permission.values()).filter(p -> !PermissionService.SYSTEM_DISABLED.contains(p)).map(Enum::name).toList(),
                m.roles().stream()
                        .sorted((a, b) -> Integer.compare(SystemRole.valueOf(a.getCode()).ordinal(), SystemRole.valueOf(b.getCode()).ordinal()))
                        .map(r -> new RolePermissionsDto(r.getId(), r.getCode(), r.getName(), r.getPermissions().stream().map(Enum::name).sorted().toList(),
                                canManage && editable(r.getCode(), m.access().isOwner())))
                        .toList(),
                canManage);
    }

    @PutMapping("/roles/{roleId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId, @PathVariable UUID roleId, @Valid @RequestBody RoleUpdateRequest req) {
        admin.updateRole(me(jwt), orgId, roleId, req.permissions());
    }

    @GetMapping("/members/{userId}/permissions")
    public MemberPermissionsDto member(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId, @PathVariable UUID userId) {
        PermissionAdminService.MemberPermissions p = admin.memberPermissions(me(jwt), orgId, userId);
        return new MemberPermissionsDto(
                userId, p.role().getCode(),
                p.role().getPermissions().stream().map(Enum::name).sorted().toList(),
                p.overrides().entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), e -> e.getValue().name())),
                p.effective().stream().map(Enum::name).sorted().toList());
    }

    @PutMapping("/members/{userId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setOverrides(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId, @PathVariable UUID userId, @Valid @RequestBody OverridesRequest req) {
        admin.setOverrides(me(jwt), orgId, userId, req.overrides());
    }

    private static boolean editable(String roleCode, boolean actorIsOwner) {
        return actorIsOwner || !(SystemRole.OWNER.name().equals(roleCode) || SystemRole.ADMIN.name().equals(roleCode));
    }

    private User me(Jwt jwt) {
        return currentUser.require(FirebasePrincipal.from(jwt));
    }

    public record RoleUpdateRequest(@NotNull Set<Permission> permissions) {
    }

    public record OverridesRequest(@NotNull Map<Permission, MemberPermission.Effect> overrides) {
    }

    public record RolePermissionsDto(UUID id, String code, String name, List<String> permissions, boolean editable) {
    }

    public record PermissionMatrixDto(List<String> definitions, List<RolePermissionsDto> roles, boolean canManage) {
    }

    public record MemberPermissionsDto(UUID userId, String role, List<String> roleDefaults, Map<String, String> overrides, List<String> effective) {
    }
}
