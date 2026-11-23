package com.dailymoney.permission;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "member_permissions")
@IdClass(MemberPermission.Key.class)
public class MemberPermission {

    public enum Effect { ALLOW, DENY }

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "perm_code")
    @Enumerated(EnumType.STRING)
    private Permission permission;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Effect effect;

    @Column(name = "set_by", nullable = false)
    private UUID setBy;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    protected MemberPermission() {
    }

    public MemberPermission(UUID orgId, UUID userId, Permission permission, Effect effect, UUID setBy, Instant setAt) {
        this.orgId = orgId;
        this.userId = userId;
        this.permission = permission;
        this.effect = effect;
        this.setBy = setBy;
        this.setAt = setAt;
    }

    public Permission getPermission() {
        return permission;
    }

    public Effect getEffect() {
        return effect;
    }

    public static class Key implements Serializable {
        private UUID orgId;
        private UUID userId;
        private Permission permission;

        public Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(orgId, k.orgId) && Objects.equals(userId, k.userId) && permission == k.permission;
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, userId, permission);
        }
    }
}
