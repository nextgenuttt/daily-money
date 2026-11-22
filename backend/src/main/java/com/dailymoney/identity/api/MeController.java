package com.dailymoney.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.application.AccountService;
import com.dailymoney.identity.application.BootstrapCommand;
import com.dailymoney.identity.domain.Device;
import com.dailymoney.identity.domain.User;
import com.dailymoney.shared.security.FirebasePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/me/bootstrap")
    public ProfileResponse bootstrap(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BootstrapRequest request) {
        User user = accounts.bootstrap(FirebasePrincipal.from(jwt), request.toCommand());
        return ProfileResponse.of(user);
    }

    @GetMapping("/me/devices")
    public List<DeviceResponse> devices(@AuthenticationPrincipal Jwt jwt) {
        return accounts.devices(FirebasePrincipal.from(jwt)).stream().map(DeviceResponse::of).toList();
    }

    @DeleteMapping("/me/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
        accounts.removeDevice(FirebasePrincipal.from(jwt), deviceId);
    }

    @PostMapping("/auth/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        accounts.signOutEverywhere(FirebasePrincipal.from(jwt));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        accounts.deleteAccount(FirebasePrincipal.from(jwt));
    }

    public record BootstrapRequest(
            @NotNull UUID deviceId,
            @Size(max = 100) String deviceName,
            @Size(max = 40) String appVersion,
            @Size(max = 4096) String fcmToken,
            @Size(max = 60) String displayName,
            @Size(max = 64) String timeZone) {

        BootstrapCommand toCommand() {
            return new BootstrapCommand(deviceId, deviceName, appVersion, fcmToken, displayName, timeZone);
        }
    }

    public record ProfileResponse(UUID id, String email, boolean emailVerified, String displayName,
            List<String> authProviders, String defaultCurrency, String timeZone, Instant createdAt) {

        static ProfileResponse of(User u) {
            return new ProfileResponse(u.getId(), u.getEmail(), u.isEmailVerified(), u.getDisplayName(),
                    List.of(u.getAuthProviders()), u.getDefaultCurrency(), u.getTimeZone(), u.getCreatedAt());
        }
    }

    public record DeviceResponse(UUID id, String platform, String deviceName, String appVersion, Instant lastSeenAt) {

        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.getId(), d.getPlatform(), d.getDeviceName(), d.getAppVersion(),
                    d.getLastSeenAt());
        }
    }
}
