package com.dailymoney.organization.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_members")
@IdClass(Membership.Key.class)
public class Membership {

    public enum Status { ACTIVE, REMOVED, LEFT }

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(nullable = false)
    private String status;

    /** Bumped on every role/permission change so cached permissions can be invalidated (Phase 6). */
    @Column(name = "perm_version", nullable = false)
    private int permVersion = 1;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected Membership() {
    }

    public static Membership join(UUID orgId, UUID userId, UUID roleId, Instant now) {
        Membership m = new Membership();
        m.orgId = orgId;
        m.userId = userId;
        m.roleId = roleId;
        m.status = Status.ACTIVE.name();
        m.joinedAt = now;
        return m;
    }

    /** A person who left or was removed can come back through a new invitation. */
    public void rejoin(UUID roleId, Instant now) {
        this.roleId = roleId;
        this.status = Status.ACTIVE.name();
        this.joinedAt = now;
        this.removedAt = null;
        this.permVersion++;
    }

    public void changeRole(UUID roleId) {
        this.roleId = roleId;
        this.permVersion++;
    }

    public void end(Status status, Instant now) {
        this.status = status.name();
        this.removedAt = now;
        this.permVersion++;
    }

    public void bumpPermVersion() {
        this.permVersion++;
    }

    public int getPermVersion() {
        return permVersion;
    }

    public boolean isActive() {
        return Status.ACTIVE.name().equals(status);
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public static class Key implements Serializable {
        private UUID orgId;
        private UUID userId;

        public Key() {
        }

        public Key(UUID orgId, UUID userId) {
            this.orgId = orgId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(orgId, k.orgId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, userId);
        }
    }
}
