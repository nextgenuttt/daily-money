package com.dailymoney.organization.infra;

import java.util.UUID;

import com.dailymoney.organization.domain.Organization;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
