package com.dailymoney.permission;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberPermissionRepository extends JpaRepository<MemberPermission, MemberPermission.Key> {

    @Query("select p from MemberPermission p where p.orgId = :orgId and p.userId = :userId")
    List<MemberPermission> forMember(UUID orgId, UUID userId);

    @Modifying
    @Query("delete from MemberPermission p where p.orgId = :orgId and p.userId = :userId")
    void deleteForMember(UUID orgId, UUID userId);
}
