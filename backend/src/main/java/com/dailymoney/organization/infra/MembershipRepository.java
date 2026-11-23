package com.dailymoney.organization.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dailymoney.organization.domain.Membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, Membership.Key> {

    @Query("select m from Membership m where m.orgId = :orgId and m.userId = :userId")
    Optional<Membership> find(UUID orgId, UUID userId);

    @Query("select m from Membership m where m.userId = :userId and m.status = 'ACTIVE'")
    List<Membership> activeForUser(UUID userId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status = 'ACTIVE' order by m.joinedAt")
    List<Membership> activeInOrg(UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status = 'ACTIVE'")
    long countActive(UUID orgId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Membership m set m.permVersion = m.permVersion + 1 where m.roleId = :roleId")
    int bumpPermVersionForRole(UUID roleId);
}
