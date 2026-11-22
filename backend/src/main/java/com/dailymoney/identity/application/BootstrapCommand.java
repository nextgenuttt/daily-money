package com.dailymoney.identity.application;

import java.util.UUID;

public record BootstrapCommand(UUID deviceId, String deviceName, String appVersion, String fcmToken,
        String displayName, String timeZone) {
}
