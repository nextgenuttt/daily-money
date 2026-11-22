package com.dailymoney.identity.infra;

import java.util.Optional;
import java.util.UUID;

import com.dailymoney.identity.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByFirebaseUid(String firebaseUid);
}
