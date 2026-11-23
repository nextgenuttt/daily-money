package com.dailymoney.organization.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dailymoney.organization.domain.OrgRole;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<OrgRole, UUID> {

    List<OrgRole> findByOrgId(UUID orgId);

    Optional<OrgRole> findByOrgIdAndCode(UUID orgId, String code);
}
