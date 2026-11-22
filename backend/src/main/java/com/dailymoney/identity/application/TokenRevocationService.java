package com.dailymoney.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.dailymoney.identity.domain.User;
import com.dailymoney.identity.infra.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.stereotype.Service;

@Service
public class TokenRevocationService {

    private final UserRepository users;
    private final Cache<String, Optional<Instant>> validAfterByUid = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(50_000)
            .build();

    public TokenRevocationService(UserRepository users) {
        this.users = users;
    }

    public boolean isRevoked(String firebaseUid, Instant issuedAt) {
        Optional<Instant> validAfter = validAfterByUid.get(firebaseUid,
                uid -> users.findByFirebaseUid(uid).map(User::getTokensValidAfter));
        return validAfter.isPresent() && issuedAt != null && issuedAt.isBefore(validAfter.get());
    }

    public void evict(String firebaseUid) {
        validAfterByUid.invalidate(firebaseUid);
    }
}
