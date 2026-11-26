package com.dailymoney.identity.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    private UUID id;

    @Column(name = "firebase_uid", nullable = false, unique = true)
    private String firebaseUid;

    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "auth_providers", nullable = false, columnDefinition = "text[]")
    private String[] authProviders = new String[0];

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "phone_e164")
    private String phoneE164;

    @Column(name = "default_currency", nullable = false, columnDefinition = "bpchar(3)")
    private String defaultCurrency = "INR";

    @Column(name = "time_zone", nullable = false)
    private String timeZone = "Asia/Kolkata";

    @Column(nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "upi_id")
    private String upiId;

    protected User() {
    }

    public static User create(UUID id, String firebaseUid, String displayName, Instant now) {
        User user = new User();
        user.id = id;
        user.firebaseUid = firebaseUid;
        user.displayName = displayName;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void updateIdentity(String email, boolean emailVerified, String provider, Instant now) {
        this.email = email == null ? null : email.trim().toLowerCase();
        this.emailVerified = emailVerified;
        if (provider != null && !hasProvider(provider)) {
            String[] next = java.util.Arrays.copyOf(authProviders, authProviders.length + 1);
            next[authProviders.length] = provider;
            this.authProviders = next;
        }
        this.updatedAt = now;
    }

    public void updateProfile(String displayName, String timeZone, Instant now) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.trim();
        }
        if (timeZone != null && !timeZone.isBlank()) {
            this.timeZone = timeZone;
        }
        this.updatedAt = now;
    }

    /** Every ID token issued before now is rejected (whole seconds, matching the token `iat`). */
    public void revokeTokens(Instant now) {
        this.tokensValidAfter = now.truncatedTo(ChronoUnit.SECONDS);
        this.updatedAt = now;
    }

    public void markDeleted(Instant now) {
        this.status = STATUS_DELETED;
        this.deletedAt = now;
        this.email = null;
        this.phoneE164 = null;
        this.displayName = "Former member";
        this.upiId = null;
        revokeTokens(now);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    private boolean hasProvider(String provider) {
        for (String p : authProviders) {
            if (p.equals(provider)) {
                return true;
            }
        }
        return false;
    }

    public UUID getId() {
        return id;
    }

    public String getFirebaseUid() {
        return firebaseUid;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String[] getAuthProviders() {
        return authProviders.clone();
    }

    public String getUpiId() {
        return upiId;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public void changePhone(String phoneE164, Instant now) {
        this.phoneE164 = phoneE164;
        this.updatedAt = now;
    }

    public void changeUpiId(String upiId, Instant now) {
        this.upiId = upiId;
        this.updatedAt = now;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
