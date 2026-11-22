package com.dailymoney.identity.infra;

import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.domain.Device;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByUserIdOrderByLastSeenAtDesc(UUID userId);

    void deleteByUserId(UUID userId);
}
