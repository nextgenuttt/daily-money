package com.dailymoney.organization.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "organizations")
public class Organization {

    public enum Kind { FAMILY, FRIENDS, OFFICE, TRAVEL, OTHER }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Organization() {
    }

    public static Organization create(UUID id, String name, Kind kind, String currency, UUID createdBy, Instant now) {
        Organization o = new Organization();
        o.id = id;
        o.name = name;
        o.kind = kind.name();
        o.currency = currency;
        o.createdBy = createdBy;
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    public void rename(String name, Kind kind, Instant now) {
        this.name = name;
        this.kind = kind.name();
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return Kind.valueOf(kind);
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
