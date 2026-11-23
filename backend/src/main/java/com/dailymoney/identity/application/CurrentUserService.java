package com.dailymoney.identity.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.dailymoney.identity.domain.User;
import com.dailymoney.identity.infra.UserRepository;
import com.dailymoney.shared.security.FirebasePrincipal;
import com.dailymoney.shared.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves the signed-in Firebase user to our profile row (created by /me/bootstrap). */
@Service
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public User require(FirebasePrincipal principal) {
        return users.findByFirebaseUid(principal.uid())
                .filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_REQUIRED", "Call /me/bootstrap after signing in"));
    }

    public Map<UUID, User> byIds(Collection<UUID> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
