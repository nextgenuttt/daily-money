package com.dailymoney.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String platform = "ANDROID";

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected Device() {
    }

    public static Device register(UUID id, UUID userId, Instant now) {
        Device device = new Device();
        device.id = id;
        device.userId = userId;
        device.createdAt = now;
        device.lastSeenAt = now;
        return device;
    }

    /** A phone can change hands: the device row follows whoever signed in last. */
    public void seen(UUID userId, String deviceName, String appVersion, String fcmToken, Instant now) {
        this.userId = userId;
        this.deviceName = deviceName;
        this.appVersion = appVersion;
        this.fcmToken = fcmToken;
        this.lastSeenAt = now;
    }

    public void clearPushToken() {
        this.fcmToken = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getFcmToken() {
        return fcmToken;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
