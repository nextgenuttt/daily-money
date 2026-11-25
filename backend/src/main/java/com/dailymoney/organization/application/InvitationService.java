package com.dailymoney.organization.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Invitation;
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

import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    static final Duration VALIDITY = Duration.ofDays(7);
    static final int MAX_PENDING_PER_ORG = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum PreviewStatus { VALID, EXPIRED, USED_UP, REVOKED, DECLINED }

    public record Created(Invitation invitation, String token, OrgRole role) {
    }

    public record Preview(Organization org, User invitedBy, OrgRole role, Invitation invitation, PreviewStatus status, boolean alreadyMember) {
    }

    private final InvitationRepository invitations;
    private final OrganizationRepository organizations;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final PermissionService permissions;
    private final AuditService audit;
    private final CurrentUserService users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public InvitationService(InvitationRepository invitations, OrganizationRepository organizations, RoleRepository roles,
            MembershipRepository memberships, PermissionService permissions, AuditService audit,
            CurrentUserService users, Clock clock, ApplicationEventPublisher events) {
        this.invitations = invitations;
        this.organizations = organizations;
        this.roles = roles;
        this.memberships = memberships;
        this.permissions = permissions;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    /** Returns the plain token once; only its hash is stored. Admin invitations need the owner. */
    @Transactional
    public Created create(User actor, UUID orgId, String roleCode, String email, int maxUses) {
        Access access = permissions.require(orgId, actor.getId(), Permission.MEMBER_INVITE);
        SystemRole role = OrganizationService.parseAssignable(roleCode);
        if (role == SystemRole.ADMIN && !access.isOwner()) throw ApiException.rule("OWNER_ONLY", "Only the owner can invite an admin");
        Instant now = clock.instant();
        if (invitations.countPending(orgId, now) >= MAX_PENDING_PER_ORG) {
            throw ApiException.rule("LIMIT_REACHED", "Too many open invitations; revoke some first");
        }
        String token = newToken();
        OrgRole orgRole = roles.findByOrgIdAndCode(orgId, role.name()).orElseThrow();
        String normalizedEmail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        Invitation invitation = invitations.save(Invitation.create(
                UuidV7.next(), orgId, actor.getId(), normalizedEmail, orgRole.getId(), hash(token),
                Math.max(1, Math.min(maxUses, 50)), now.plus(VALIDITY), now));
        audit.record(actor.getId(), orgId, "INVITATION", invitation.getId(), "INVITE_CREATE", role.name());
        return new Created(invitation, token, orgRole);
    }

    @Transactional(readOnly = true)
    public List<Invitation> pending(User actor, UUID orgId) {
        permissions.require(orgId, actor.getId(), Permission.MEMBER_INVITE);
        return invitations.pending(orgId, clock.instant());
    }

    @Transactional
    public void revoke(User actor, UUID invitationId) {
        Invitation invitation = invitations.findById(invitationId).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        permissions.require(invitation.getOrgId(), actor.getId(), Permission.MEMBER_INVITE);
        invitation.revoke();
        audit.record(actor.getId(), invitation.getOrgId(), "INVITATION", invitationId, "INVITE_REVOKE", null);
    }

    /** What the join screen shows before the user accepts. Unknown tokens are simply "not found". */
    @Transactional(readOnly = true)
    public Preview preview(User actor, String token) {
        Invitation invitation = invitations.findByHash(hash(token)).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        Organization org = organizations.findById(invitation.getOrgId()).filter(o -> !o.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Invitation not found"));
        boolean member = memberships.find(org.getId(), actor.getId()).map(Membership::isActive).orElse(false);
        User inviter = users.byIds(List.of(invitation.getInvitedBy())).get(invitation.getInvitedBy());
        return new Preview(org, inviter, roles.findById(invitation.getRoleId()).orElseThrow(), invitation, status(invitation), member);
    }

    @Transactional
    public Organization accept(User actor, String token) {
        Invitation invitation = invitations.findForUpdate(hash(token)).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        Organization org = organizations.findById(invitation.getOrgId()).filter(o -> !o.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Invitation not found"));
        PreviewStatus status = status(invitation);
        if (status != PreviewStatus.VALID) {
            throw new ApiException(HttpStatus.GONE, "INVITE_" + status.name(), "This invitation can no longer be used");
        }
        checkEmail(actor, invitation);
        Instant now = clock.instant();
        Membership existing = memberships.find(org.getId(), actor.getId()).orElse(null);
        if (existing != null && existing.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER", "You are already in this group");
        }
        if (existing != null) {
            existing.rejoin(invitation.getRoleId(), now);
        } else {
            memberships.save(Membership.join(org.getId(), actor.getId(), invitation.getRoleId(), now));
        }
        invitation.use();
        audit.record(actor.getId(), org.getId(), "MEMBER", actor.getId(), "MEMBER_JOIN", invitation.getId().toString());
        events.publishEvent(SyncPoke.users(actor.getId()));
        return org;
    }

    /** Only invitations sent to this person's email can be declined; link invites are just ignored. */
    @Transactional
    public void decline(User actor, String token) {
        Invitation invitation = invitations.findForUpdate(hash(token)).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        if (invitation.getInviteeEmail() == null) return;
        checkEmail(actor, invitation);
        if (status(invitation) == PreviewStatus.VALID) invitation.decline();
    }

    private void checkEmail(User actor, Invitation invitation) {
        String bound = invitation.getInviteeEmail();
        if (bound == null) return;
        if (!actor.isEmailVerified() || actor.getEmail() == null || !bound.equals(actor.getEmail())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVITE_EMAIL_MISMATCH", "This invitation was sent to a different email address");
        }
    }

    private PreviewStatus status(Invitation i) {
        return switch (i.getStatus()) {
            case USED_UP -> PreviewStatus.USED_UP;
            case REVOKED -> PreviewStatus.REVOKED;
            case DECLINED -> PreviewStatus.DECLINED;
            case PENDING -> i.isExpired(clock.instant()) ? PreviewStatus.EXPIRED : PreviewStatus.VALID;
        };
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
