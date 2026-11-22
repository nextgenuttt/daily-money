package com.dailymoney.shared.security;

import java.util.Map;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Extra checks on Firebase ID tokens beyond signature, issuer and expiry. */
public final class FirebaseTokenValidators {

    private FirebaseTokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> audience(String projectId) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(projectId)
                ? OAuth2TokenValidatorResult.success()
                : failure("invalid_token", "Token audience does not match the Firebase project");
    }

    /** Email/password accounts must verify their email before using the API (doc 04 §1). */
    public static OAuth2TokenValidator<Jwt> emailVerifiedForPasswordAccounts() {
        return jwt -> {
            if (!"password".equals(signInProvider(jwt))) {
                return OAuth2TokenValidatorResult.success();
            }
            return Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                    ? OAuth2TokenValidatorResult.success()
                    : failure("email_not_verified", "Email address is not verified");
        };
    }

    public static String signInProvider(Jwt jwt) {
        Object firebase = jwt.getClaims().get("firebase");
        if (firebase instanceof Map<?, ?> map && map.get("sign_in_provider") instanceof String provider) {
            return provider;
        }
        return null;
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
