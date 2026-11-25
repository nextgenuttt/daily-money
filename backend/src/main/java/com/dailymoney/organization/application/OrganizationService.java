package com.dailymoney.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.domain.OrgRole;
import com.dailymoney.organization.domain.Organization;
import com.dailymoney.organization.infra.InvitationRepository;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.organization.infra.OrganizationRepository;
import com.dailymoney.organization.infra.RoleRepository;
import com.dailymoney.permission.Permission;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.permission.PermissionService.Access;
import com.dailymoney.permission.SystemRole;
import com.dailymoney.shared.id.UuidV7;
import com.dailymoney.shared.web.ApiException;
import com.dailymoney.sync.SyncPoke;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    /** Abuse guard: one person can't create unlimited groups. */
    static final int MAX_ORGS_PER_USER = 20;

    public record Summary(Organization org, OrgRole myRole, long memberCount) {
    }

    public record Detail(Organization org, Access access, List<OrgRole> roles, long memberCount) {
    }

    public record MemberView(Membership membership, User user, OrgRole role) {
    }

    private final OrganizationRepository organizations;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final PermissionService permissions;
    private final AuditService audit;
    private final CurrentUserService users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public OrganizationService(OrganizationRepository organizations, RoleRepository roles, MembershipRepository memberships,
            InvitationRepository invitations, PermissionService permissions, AuditService audit,
            CurrentUserService users, Clock clock, ApplicationEventPublisher events) {
        this.organizations = organizations;
        this.roles = roles;
        this.memberships = memberships;
        this.invitations = invitations;
        this.permissions = permissions;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    /** The creator becomes Owner; the four system roles are seeded with the default matrix (LLD §5.3). */
    @Transactional
    public Organization create(User actor, String name, Organization.Kind kind, String currency) {
        if (memberships.activeForUser(actor.getId()).size() >= MAX_ORGS_PER_USER) {
            throw ApiException.rule("LIMIT_REACHED", "You can be in at most " + MAX_ORGS_PER_USER + " groups");
        }
        Instant now = clock.instant();
        Organization org = organizations.save(Organization.create(UuidV7.next(), name.trim(), kind, currency, actor.getId(), now));
        OrgRole owner = null;
        for (SystemRole r : SystemRole.values()) {
            OrgRole saved = roles.save(OrgRole.system(UuidV7.next(), org.getId(), r.name(), r.displayName(), r.defaults()));
            if (r == SystemRole.OWNER) owner = saved;
        }
        memberships.save(Membership.join(org.getId(), actor.getId(), owner.getId(), now));
        audit.record(actor.getId(), org.getId(), "ORGANIZATION", org.getId(), "ORG_CREATE", org.getName());
        return org;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(User actor) {
        return memberships.activeForUser(actor.getId()).stream()
                .map(m -> organizations.findById(m.getOrgId())
                        .filter(o -> !o.isDeleted())
                        .map(o -> new Summary(o, roles.findById(m.getRoleId()).orElseThrow(), memberships.countActive(o.getId())))
                        .orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Summary s) -> s.org().getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Detail get(User actor, UUID orgId) {
        Access access = permissions.member(orgId, actor.getId());
        Organization org = organizations.findById(orgId).orElseThrow();
        return new Detail(org, access, roles.findByOrgId(orgId), memberships.countActive(orgId));
    }

    @Transactional
    public Organization update(User actor, UUID orgId, String name, Organization.Kind kind) {
        permissions.require(orgId, actor.getId(), Permission.ORGANIZATION_SETTINGS_MANAGE);
        Organization org = organizations.findById(orgId).orElseThrow();
        org.rename(name.trim(), kind, clock.instant());
        audit.record(actor.getId(), orgId, "ORGANIZATION", orgId, "ORG_UPDATE", org.getName());
        return organizations.save(org);
    }

    /** Owner only. Soft delete: shared financial history is kept; open invitations stop working. */
    @Transactional
    public void delete(User actor, UUID orgId) {
        Access access = permissions.member(orgId, actor.getId());
        if (!access.isOwner()) throw ApiException.rule("OWNER_ONLY", "Only the owner can delete the group");
        Instant now = clock.instant();
        Organization org = organizations.findById(orgId).orElseThrow();
        org.delete(now);
        invitations.pending(orgId, now).forEach(i -> i.revoke());
        audit.record(actor.getId(), orgId, "ORGANIZATION", orgId, "ORG_DELETE", null);
        events.publishEvent(SyncPoke.org(orgId));
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(User actor, UUID orgId) {
        permissions.member(orgId, actor.getId());
        List<Membership> active = memberships.activeInOrg(orgId);
        Map<UUID, User> people = users.byIds(active.stream().map(Membership::getUserId).toList());
        Map<UUID, OrgRole> roleById = roles.findByOrgId(orgId).stream().collect(Collectors.toMap(OrgRole::getId, r -> r));
        return active.stream()
                .map(m -> new MemberView(m, people.get(m.getUserId()), roleById.get(m.getRoleId())))
                .sorted(Comparator.comparing((MemberView v) -> SystemRole.valueOf(v.role().getCode()).ordinal())
                        .thenComparing(v -> v.membership().getJoinedAt()))
                .toList();
    }

    /**
     * Guard rails (LLD §5.3): the owner's role only changes through a transfer, nobody can make
     * themselves or others owner here, and only the owner can change or remove admins.
     */
    @Transactional
    public void changeRole(User actor, UUID orgId, UUID userId, String roleCode) {
        Access access = permissions.require(orgId, actor.getId(), Permission.MEMBER_MANAGE);
        if (actor.getId().equals(userId)) throw ApiException.rule("CANNOT_CHANGE_SELF", "You can't change your own role");
        Membership target = activeMember(orgId, userId);
        OrgRole current = roles.findById(target.getRoleId()).orElseThrow();
        SystemRole wanted = parseAssignable(roleCode);
        guardTarget(access, current);
        if (wanted == SystemRole.ADMIN && !access.isOwner()) throw ApiException.rule("OWNER_ONLY", "Only the owner can make someone an admin");
        OrgRole role = roles.findByOrgIdAndCode(orgId, wanted.name()).orElseThrow();
        target.changeRole(role.getId());
        audit.record(actor.getId(), orgId, "MEMBER", userId, "ROLE_CHANGE", current.getCode() + "->" + role.getCode());
        events.publishEvent(SyncPoke.users(userId));
    }

    @Transactional
    public void removeMember(User actor, UUID orgId, UUID userId) {
        Access access = permissions.require(orgId, actor.getId(), Permission.MEMBER_MANAGE);
        if (actor.getId().equals(userId)) throw ApiException.rule("USE_LEAVE", "Use leave to exit the group yourself");
        Membership target = activeMember(orgId, userId);
        guardTarget(access, roles.findById(target.getRoleId()).orElseThrow());
        target.end(Membership.Status.REMOVED, clock.instant());
        audit.record(actor.getId(), orgId, "MEMBER", userId, "MEMBER_REMOVE", null);
        events.publishEvent(SyncPoke.users(userId));
    }

    /** The owner must hand over first, unless they are the last member (then the group is deleted). */
    @Transactional
    public void leave(User actor, UUID orgId) {
        Access access = permissions.member(orgId, actor.getId());
        Instant now = clock.instant();
        if (access.isOwner()) {
            if (memberships.countActive(orgId) > 1) {
                throw ApiException.rule("TRANSFER_OWNERSHIP_FIRST", "Make someone else the owner before leaving");
            }
            organizations.findById(orgId).orElseThrow().delete(now);
            invitations.pending(orgId, now).forEach(i -> i.revoke());
        }
        access.membership().end(Membership.Status.LEFT, now);
        audit.record(actor.getId(), orgId, "MEMBER", actor.getId(), "MEMBER_LEAVE", null);
        events.publishEvent(SyncPoke.users(actor.getId()));
    }

    /**
     * Account deletion: leaves every group. An owner must hand a group with other members to someone
     * first (nothing changes until all such groups are handled); a group they're alone in is deleted.
     */
    @Transactional
    public void leaveAllForDeletion(User user) {
        List<Membership> mine = memberships.activeForUser(user.getId());
        String ownerRole = SystemRole.OWNER.name();
        List<String> blocking = mine.stream()
                .filter(m -> ownerRole.equals(roles.findById(m.getRoleId()).map(OrgRole::getCode).orElse(null)))
                .filter(m -> memberships.countActive(m.getOrgId()) > 1)
                .map(m -> organizations.findById(m.getOrgId()).map(Organization::getName).orElse("?"))
                .sorted()
                .toList();
        if (!blocking.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_OWNERSHIP_FIRST",
                    "Make someone else the owner first: " + String.join(", ", blocking));
        }
        mine.forEach(m -> leave(user, m.getOrgId()));
    }

    @Transactional
    public void transferOwnership(User actor, UUID orgId, UUID newOwnerId) {
        Access access = permissions.member(orgId, actor.getId());
        if (!access.isOwner()) throw ApiException.rule("OWNER_ONLY", "Only the owner can transfer ownership");
        if (actor.getId().equals(newOwnerId)) throw ApiException.rule("ALREADY_OWNER", "You are already the owner");
        Membership target = activeMember(orgId, newOwnerId);
        target.changeRole(roles.findByOrgIdAndCode(orgId, SystemRole.OWNER.name()).orElseThrow().getId());
        access.membership().changeRole(roles.findByOrgIdAndCode(orgId, SystemRole.ADMIN.name()).orElseThrow().getId());
        audit.record(actor.getId(), orgId, "MEMBER", newOwnerId, "OWNER_TRANSFER", null);
        events.publishEvent(SyncPoke.users(actor.getId(), newOwnerId));
    }

    static SystemRole parseAssignable(String code) {
        SystemRole role;
        try {
            role = SystemRole.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.rule("INVALID_ROLE", "Unknown role " + code);
        }
        if (role == SystemRole.OWNER) throw ApiException.rule("USE_TRANSFER", "Use transfer ownership to change the owner");
        return role;
    }

    private Membership activeMember(UUID orgId, UUID userId) {
        return memberships.find(orgId, userId).filter(Membership::isActive)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
    }

    private static void guardTarget(Access actor, OrgRole target) {
        if (SystemRole.OWNER.name().equals(target.getCode())) throw ApiException.rule("CANNOT_MODIFY_OWNER", "The owner can't be changed or removed");
        if (SystemRole.ADMIN.name().equals(target.getCode()) && !actor.isOwner()) {
            throw ApiException.rule("OWNER_ONLY", "Only the owner can change or remove an admin");
        }
    }

    public static Set<String> assignableRoles() {
        return Set.of(SystemRole.ADMIN.name(), SystemRole.MEMBER.name(), SystemRole.VIEWER.name());
    }
}
