package com.dailymoney.organization.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.permission.Permission;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_roles")
public class OrgRole {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "perm_code")
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    protected OrgRole() {
    }

    public static OrgRole system(UUID id, UUID orgId, String code, String name, Set<Permission> permissions) {
        OrgRole r = new OrgRole();
        r.id = id;
        r.orgId = orgId;
        r.code = code;
        r.name = name;
        r.system = true;
        r.permissions = new HashSet<>(permissions);
        return r;
    }

    public void replacePermissions(Set<Permission> wanted) {
        permissions.clear();
        permissions.addAll(wanted);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return Set.copyOf(permissions);
    }
}
