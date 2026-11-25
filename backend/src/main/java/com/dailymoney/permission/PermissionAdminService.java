package com.dailymoney.permission;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.domain.OrgRole;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.organization.infra.RoleRepository;
import com.dailymoney.permission.PermissionService.Access;
import com.dailymoney.shared.web.ApiException;
import com.dailymoney.sync.SyncPoke;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing who may do what (spec §12, LLD §5.3 guard rails):
 * - only the owner edits the Owner and Admin roles or an admin's overrides, and the Owner role always keeps
 *   the management permissions (so a group can never be locked);
 * - nobody changes their own permissions;
 * - except for the owner, you can only grant (ALLOW / add to a role) permissions you hold yourself;
 *   denying needs PERMISSION_MANAGE only.
 */
@Service
public class PermissionAdminService {

    static final Set<Permission> OWNER_LOCKED = EnumSet.of(
            Permission.MEMBER_INVITE, Permission.MEMBER_MANAGE, Permission.PERMISSION_MANAGE, Permission.ORGANIZATION_SETTINGS_MANAGE);

    public record RoleMatrix(List<OrgRole> roles, Access access) {
    }

    public record MemberPermissions(Membership membership, OrgRole role, Map<Permission, MemberPermission.Effect> overrides, Set<Permission> effective) {
    }

    private final PermissionService permissions;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final MemberPermissionRepository overrides;
    private final AuditService audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public PermissionAdminService(PermissionService permissions, RoleRepository roles, MembershipRepository memberships,
            MemberPermissionRepository overrides, AuditService audit, Clock clock, ApplicationEventPublisher events) {
        this.permissions = permissions;
        this.roles = roles;
        this.memberships = memberships;
        this.overrides = overrides;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public RoleMatrix matrix(User actor, UUID orgId) {
        Access access = permissions.member(orgId, actor.getId());
        return new RoleMatrix(roles.findByOrgId(orgId), access);
    }

    @Transactional
    public void updateRole(User actor, UUID orgId, UUID roleId, Set<Permission> wanted) {
        Access access = permissions.require(orgId, actor.getId(), Permission.PERMISSION_MANAGE);
        OrgRole role = roles.findById(roleId).filter(r -> r.getOrgId().equals(orgId)).orElseThrow(() -> ApiException.notFound("Role not found"));
        boolean ownerRole = SystemRole.OWNER.name().equals(role.getCode());
        if ((ownerRole || SystemRole.ADMIN.name().equals(role.getCode())) && !access.isOwner()) {
            throw ApiException.rule("OWNER_ONLY", "Only the owner can change the owner and admin roles");
        }
        if (ownerRole && !wanted.containsAll(OWNER_LOCKED)) {
            throw ApiException.rule("OWNER_KEEPS_MANAGEMENT", "The owner role must keep member and settings management");
        }
        EnumSet<Permission> added = wanted.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(wanted);
        added.removeAll(role.getPermissions());
        checkCanGrant(access, added);
        Set<Permission> before = role.getPermissions();
        role.replacePermissions(wanted);
        roles.save(role);
        // Everyone with this role gets a new permVersion, so cached permissions refresh immediately.
        memberships.bumpPermVersionForRole(roleId);
        audit.record(actor.getId(), orgId, "ROLE", roleId, "PERM_CHANGE", before + "->" + wanted);
        events.publishEvent(SyncPoke.org(orgId));
    }

    @Transactional(readOnly = true)
    public MemberPermissions memberPermissions(User actor, UUID orgId, UUID userId) {
        Access access = permissions.member(orgId, actor.getId());
        // Anyone may see their own permissions; seeing others' needs PERMISSION_MANAGE.
        if (!actor.getId().equals(userId) && !access.has(Permission.PERMISSION_MANAGE)) {
            permissions.require(orgId, actor.getId(), Permission.PERMISSION_MANAGE);
        }
        Access target = permissions.member(orgId, userId);
        Map<Permission, MemberPermission.Effect> current = overrides.forMember(orgId, userId).stream()
                .collect(Collectors.toMap(MemberPermission::getPermission, MemberPermission::getEffect));
        return new MemberPermissions(target.membership(), target.role(), current, target.permissions());
    }

    /** Replaces the member's overrides with [wanted] (permissions not in the map follow the role). */
    @Transactional
    public void setOverrides(User actor, UUID orgId, UUID userId, Map<Permission, MemberPermission.Effect> wanted) {
        Access access = permissions.require(orgId, actor.getId(), Permission.PERMISSION_MANAGE);
        if (actor.getId().equals(userId)) throw ApiException.rule("CANNOT_CHANGE_SELF", "You can't change your own permissions");
        Access target = permissions.member(orgId, userId);
        if (target.isOwner()) throw ApiException.rule("CANNOT_MODIFY_OWNER", "Change the owner's permissions through the owner role");
        if (SystemRole.ADMIN.name().equals(target.role().getCode()) && !access.isOwner()) {
            throw ApiException.rule("OWNER_ONLY", "Only the owner can change an admin's permissions");
        }
        Set<Permission> allows = wanted.entrySet().stream().filter(e -> e.getValue() == MemberPermission.Effect.ALLOW)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        checkCanGrant(access, allows);
        Instant now = clock.instant();
        overrides.deleteForMember(orgId, userId);
        overrides.flush();
        wanted.forEach((p, effect) -> overrides.save(new MemberPermission(orgId, userId, p, effect, actor.getId(), now)));
        target.membership().bumpPermVersion();
        memberships.save(target.membership());
        audit.record(actor.getId(), orgId, "MEMBER", userId, "PERM_OVERRIDE", wanted.toString());
        events.publishEvent(SyncPoke.users(userId));
    }

    private static void checkCanGrant(Access actor, Set<Permission> grants) {
        if (grants.stream().anyMatch(PermissionService.SYSTEM_DISABLED::contains)) {
            throw ApiException.rule("PERMISSION_UNAVAILABLE", "That feature isn't available yet");
        }
        if (actor.isOwner()) return;
        Set<Permission> unheld = grants.stream().filter(p -> !actor.has(p)).collect(Collectors.toSet());
        if (!unheld.isEmpty()) throw ApiException.rule("CANNOT_GRANT_UNHELD", "You can't grant permissions you don't have: " + unheld);
    }
}
