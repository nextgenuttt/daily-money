package com.dailymoney.organization.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.application.InvitationService;
import com.dailymoney.organization.application.OrganizationService;
import com.dailymoney.organization.domain.Invitation;
import com.dailymoney.organization.domain.OrgRole;
import com.dailymoney.organization.domain.Organization;
import com.dailymoney.permission.Permission;
import com.dailymoney.shared.security.FirebasePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OrganizationController {

    @ConfigurationProperties(prefix = "app.invite")
    public record InviteProperties(String linkBase) {
        String link(String token) {
            String base = linkBase == null || linkBase.isBlank() ? "https://dailymoney-app.web.app/invite/" : linkBase;
            return (base.endsWith("/") ? base : base + "/") + token;
        }
    }

    private final OrganizationService organizations;
    private final InvitationService invitations;
    private final CurrentUserService currentUser;
    private final InviteProperties inviteProperties;

    public OrganizationController(OrganizationService organizations, InvitationService invitations, CurrentUserService currentUser,
            InviteProperties inviteProperties) {
        this.organizations = organizations;
        this.invitations = invitations;
        this.currentUser = currentUser;
        this.inviteProperties = inviteProperties;
    }

    // ---- organizations

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationDetailDto create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrganizationRequest req) {
        User me = me(jwt);
        Organization org = organizations.create(me, req.name(), req.kind(), req.currency() == null ? me.getDefaultCurrency() : req.currency());
        return OrganizationDetailDto.of(organizations.get(me, org.getId()));
    }

    @GetMapping("/organizations")
    public List<OrganizationSummaryDto> list(@AuthenticationPrincipal Jwt jwt) {
        return organizations.list(me(jwt)).stream().map(OrganizationSummaryDto::of).toList();
    }

    @GetMapping("/organizations/{id}")
    public OrganizationDetailDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OrganizationDetailDto.of(organizations.get(me(jwt), id));
    }

    @PutMapping("/organizations/{id}")
    public OrganizationDetailDto update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest req) {
        User me = me(jwt);
        organizations.update(me, id, req.name(), req.kind());
        return OrganizationDetailDto.of(organizations.get(me, id));
    }

    @DeleteMapping("/organizations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        organizations.delete(me(jwt), id);
    }

    // ---- members

    @GetMapping("/organizations/{id}/members")
    public List<MemberDto> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        User me = me(jwt);
        boolean canManage = organizations.get(me, id).access().has(Permission.MEMBER_MANAGE);
        return organizations.members(me, id).stream().map(v -> MemberDto.of(v, me.getId(), canManage)).toList();
    }

    @PutMapping("/organizations/{id}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId, @Valid @RequestBody RoleRequest req) {
        organizations.changeRole(me(jwt), id, userId, req.role());
    }

    @DeleteMapping("/organizations/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
        organizations.removeMember(me(jwt), id, userId);
    }

    @PostMapping("/organizations/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        organizations.leave(me(jwt), id);
    }

    @PostMapping("/organizations/{id}/transfer-ownership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody TransferRequest req) {
        organizations.transferOwnership(me(jwt), id, req.userId());
    }

    // ---- invitations

    @PostMapping("/organizations/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedInvitationDto invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody InviteRequest req) {
        InvitationService.Created created = invitations.create(me(jwt), id, req.role(), req.email(), req.maxUses() == null ? 1 : req.maxUses());
        return new CreatedInvitationDto(InvitationDto.of(created.invitation(), created.role()), created.token(), inviteProperties.link(created.token()));
    }

    @GetMapping("/organizations/{id}/invitations")
    public List<InvitationDto> pendingInvitations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        User me = me(jwt);
        var roles = organizations.get(me, id).roles();
        return invitations.pending(me, id).stream()
                .map(i -> InvitationDto.of(i, roles.stream().filter(r -> r.getId().equals(i.getRoleId())).findFirst().orElseThrow()))
                .toList();
    }

    @DeleteMapping("/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        invitations.revoke(me(jwt), invitationId);
    }

    @GetMapping("/invitations/by-token/{token}")
    public InvitePreviewDto preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return InvitePreviewDto.of(invitations.preview(me(jwt), token));
    }

    @PostMapping("/invitations/by-token/{token}/accept")
    public OrganizationSummaryDto accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        User me = me(jwt);
        Organization org = invitations.accept(me, token);
        return organizations.list(me).stream().filter(s -> s.org().getId().equals(org.getId())).findFirst().map(OrganizationSummaryDto::of).orElseThrow();
    }

    @PostMapping("/invitations/by-token/{token}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        invitations.decline(me(jwt), token);
    }

    private User me(Jwt jwt) {
        return currentUser.require(FirebasePrincipal.from(jwt));
    }

    // ---- request / response shapes

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull Organization.Kind kind,
            @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record UpdateOrganizationRequest(@NotBlank @Size(max = 60) String name, @NotNull Organization.Kind kind) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record TransferRequest(@NotNull UUID userId) {
    }

    public record InviteRequest(@NotBlank String role, @Email @Size(max = 254) String email, @Min(1) @Max(50) Integer maxUses) {
    }

    public record RoleDto(UUID id, String code, String name) {
        static RoleDto of(OrgRole r) {
            return new RoleDto(r.getId(), r.getCode(), r.getName());
        }
    }

    public record OrganizationSummaryDto(UUID id, String name, Organization.Kind kind, String currency, String myRole, long memberCount) {
        static OrganizationSummaryDto of(OrganizationService.Summary s) {
            return new OrganizationSummaryDto(s.org().getId(), s.org().getName(), s.org().getKind(), s.org().getCurrency(),
                    s.myRole().getCode(), s.memberCount());
        }
    }

    public record OrganizationDetailDto(UUID id, String name, Organization.Kind kind, String currency, String myRole,
            List<String> myPermissions, List<RoleDto> roles, long memberCount, Instant createdAt) {
        static OrganizationDetailDto of(OrganizationService.Detail d) {
            return new OrganizationDetailDto(d.org().getId(), d.org().getName(), d.org().getKind(), d.org().getCurrency(),
                    d.access().role().getCode(), d.access().permissions().stream().map(Enum::name).sorted().toList(),
                    d.roles().stream().map(RoleDto::of).toList(), d.memberCount(), d.org().getCreatedAt());
        }
    }

    /** Email is shown only to people who manage members (privacy, spec §1). */
    public record MemberDto(UUID userId, String displayName, String email, String role, Instant joinedAt, boolean isMe) {
        static MemberDto of(OrganizationService.MemberView v, UUID me, boolean canSeeEmail) {
            return new MemberDto(v.membership().getUserId(), v.user() == null ? "Former member" : v.user().getDisplayName(),
                    canSeeEmail && v.user() != null ? v.user().getEmail() : null, v.role().getCode(), v.membership().getJoinedAt(),
                    v.membership().getUserId().equals(me));
        }
    }

    public record InvitationDto(UUID id, String role, String email, int maxUses, int uses, Instant expiresAt, Instant createdAt) {
        static InvitationDto of(Invitation i, OrgRole role) {
            return new InvitationDto(i.getId(), role.getCode(), i.getInviteeEmail(), i.getMaxUses(), i.getUses(), i.getExpiresAt(), i.getCreatedAt());
        }
    }

    public record CreatedInvitationDto(InvitationDto invitation, String token, String link) {
    }

    public record InvitePreviewDto(UUID organizationId, String organizationName, Organization.Kind kind, String invitedBy, String role,
            Instant expiresAt, String status, boolean alreadyMember, boolean emailBound) {
        static InvitePreviewDto of(InvitationService.Preview p) {
            return new InvitePreviewDto(p.org().getId(), p.org().getName(), p.org().getKind(),
                    p.invitedBy() == null ? null : p.invitedBy().getDisplayName(), p.role().getCode(), p.invitation().getExpiresAt(),
                    p.status().name(), p.alreadyMember(), p.invitation().getInviteeEmail() != null);
        }
    }
}
