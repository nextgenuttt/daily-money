package com.dailymoney.permission;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.domain.OrgRole;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.organization.infra.OrganizationRepository;
import com.dailymoney.organization.infra.RoleRepository;
import com.dailymoney.shared.web.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The single place organization access is decided (LLD §5.1):
 * 1. active membership of a live organization, 2. role permissions, 3. member overrides
 * (ALLOW adds; DENY removes and always wins over the role), 4. system-wide restrictions.
 * Resource rules such as creator-only editing are enforced separately and can't be overridden.
 *
 * Effective sets are cached per (org, user, permVersion); any role or override change bumps
 * permVersion, so a stale entry is simply never looked up again.
 */
@Service
public class PermissionService {

    /** Features not available in this build; nobody can hold these until they ship (spec §17 SMS is optional). */
    static final Set<Permission> SYSTEM_DISABLED = EnumSet.of(Permission.EXPENSE_SMS_SEND, Permission.EXPENSE_SMS_NOTIFICATION);

    public record Access(Membership membership, OrgRole role, Set<Permission> permissions) {

        public boolean has(Permission p) {
            return permissions.contains(p);
        }

        public boolean isOwner() {
            return SystemRole.OWNER.name().equals(role.getCode());
        }
    }

    private record CacheKey(UUID orgId, UUID userId, int permVersion) {
    }

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final MemberPermissionRepository overrides;
    private final Cache<CacheKey, Set<Permission>> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public PermissionService(OrganizationRepository organizations, MembershipRepository memberships, RoleRepository roles,
            MemberPermissionRepository overrides) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.roles = roles;
        this.overrides = overrides;
    }

    /**
     * Active member of a live organization, or 404. Non-members get "not found" rather than "forbidden"
     * so they can't even learn the organization exists (LLD §11).
     */
    public Access member(UUID orgId, UUID userId) {
        boolean live = organizations.findById(orgId).map(o -> !o.isDeleted()).orElse(false);
        Membership m = memberships.find(orgId, userId).filter(Membership::isActive).orElse(null);
        if (!live || m == null) {
            throw ApiException.notFound("Organization not found");
        }
        OrgRole role = roles.findById(m.getRoleId()).orElseThrow(() -> ApiException.notFound("Organization not found"));
        Set<Permission> effective = cache.get(new CacheKey(orgId, userId, m.getPermVersion()),
                k -> effective(role.getPermissions(), overrides.forMember(orgId, userId)));
        return new Access(m, role, effective);
    }

    public Access require(UUID orgId, UUID userId, Permission permission) {
        Access access = member(orgId, userId);
        if (!access.has(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", permission.name() + " is not granted");
        }
        return access;
    }

    static Set<Permission> effective(Set<Permission> roleDefaults, List<MemberPermission> memberOverrides) {
        EnumSet<Permission> result = roleDefaults.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(roleDefaults);
        memberOverrides.stream().filter(o -> o.getEffect() == MemberPermission.Effect.ALLOW).forEach(o -> result.add(o.getPermission()));
        // Deny is applied last so it always beats both the role and any allow.
        memberOverrides.stream().filter(o -> o.getEffect() == MemberPermission.Effect.DENY).forEach(o -> result.remove(o.getPermission()));
        result.removeAll(SYSTEM_DISABLED);
        return Set.copyOf(result);
    }
}
