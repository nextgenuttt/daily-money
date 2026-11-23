package com.dailymoney.organization.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A join link. Only a SHA-256 hash of the token is stored, so a database leak can't be used to join.
 * Bound to an email when [inviteeEmail] is set; otherwise anyone with the link can use it, up to [maxUses].
 */
@Entity
@Table(name = "organization_invitations")
public class Invitation {

    public enum Status { PENDING, USED_UP, DECLINED, REVOKED }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "invitee_email")
    private String inviteeEmail;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private byte[] tokenHash;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(nullable = false)
    private int uses;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Invitation() {
    }

    public static Invitation create(UUID id, UUID orgId, UUID invitedBy, String email, UUID roleId, byte[] tokenHash,
            int maxUses, Instant expiresAt, Instant now) {
        Invitation i = new Invitation();
        i.id = id;
        i.orgId = orgId;
        i.invitedBy = invitedBy;
        i.inviteeEmail = email;
        i.roleId = roleId;
        i.tokenHash = tokenHash;
        i.maxUses = email != null ? 1 : maxUses;
        i.status = Status.PENDING.name();
        i.expiresAt = expiresAt;
        i.createdAt = now;
        return i;
    }

    public void use() {
        uses++;
        if (uses >= maxUses) {
            status = Status.USED_UP.name();
        }
    }

    public void revoke() {
        status = Status.REVOKED.name();
    }

    public void decline() {
        status = Status.DECLINED.name();
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public String getInviteeEmail() {
        return inviteeEmail;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public int getUses() {
        return uses;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
