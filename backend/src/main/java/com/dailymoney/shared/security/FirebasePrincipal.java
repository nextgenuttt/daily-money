package com.dailymoney.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The signed-in caller, read from a verified Firebase ID token. */
public record FirebasePrincipal(String uid, String email, boolean emailVerified, String name, String signInProvider) {

    public static FirebasePrincipal from(Jwt jwt) {
        return new FirebasePrincipal(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                jwt.getClaimAsString("name"),
                FirebaseTokenValidators.signInProvider(jwt));
    }
}
