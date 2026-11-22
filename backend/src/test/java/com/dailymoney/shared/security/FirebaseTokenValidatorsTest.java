package com.dailymoney.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class FirebaseTokenValidatorsTest {

    private static Jwt token(String aud, String provider, Boolean emailVerified) {
        Jwt.Builder b = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("uid-1")
                .audience(List.of(aud))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("firebase", Map.of("sign_in_provider", provider));
        if (emailVerified != null) {
            b.claim("email_verified", emailVerified);
        }
        return b.build();
    }

    @Test
    void audienceMustMatchProject() {
        assertThat(FirebaseTokenValidators.audience("proj").validate(token("proj", "password", true)).hasErrors())
                .isFalse();
        assertThat(FirebaseTokenValidators.audience("proj").validate(token("other", "password", true)).hasErrors())
                .isTrue();
    }

    @Test
    void unverifiedPasswordAccountIsRejected() {
        var validator = FirebaseTokenValidators.emailVerifiedForPasswordAccounts();
        assertThat(validator.validate(token("p", "password", false)).hasErrors()).isTrue();
        assertThat(validator.validate(token("p", "password", null)).hasErrors()).isTrue();
        assertThat(validator.validate(token("p", "password", true)).hasErrors()).isFalse();
    }

    @Test
    void googleAccountDoesNotNeedEmailVerificationClaim() {
        var validator = FirebaseTokenValidators.emailVerifiedForPasswordAccounts();
        assertThat(validator.validate(token("p", "google.com", null)).hasErrors()).isFalse();
    }

    @Test
    void principalReadsClaims() {
        FirebasePrincipal p = FirebasePrincipal.from(token("p", "google.com", true));
        assertThat(p.uid()).isEqualTo("uid-1");
        assertThat(p.signInProvider()).isEqualTo("google.com");
        assertThat(p.emailVerified()).isTrue();
    }
}
