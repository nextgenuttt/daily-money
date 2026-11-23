package com.dailymoney.organization.infra;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dailymoney.organization.domain.Invitation;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** Row lock so two people can't both take the last use of a link at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :hash")
    Optional<Invitation> findForUpdate(byte[] hash);

    @Query("select i from Invitation i where i.tokenHash = :hash")
    Optional<Invitation> findByHash(byte[] hash);

    @Query("select i from Invitation i where i.orgId = :orgId and i.status = 'PENDING' and i.expiresAt > :now order by i.createdAt desc")
    List<Invitation> pending(UUID orgId, Instant now);

    @Query("select count(i) from Invitation i where i.orgId = :orgId and i.status = 'PENDING' and i.expiresAt > :now")
    long countPending(UUID orgId, Instant now);
}
