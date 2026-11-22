package com.dailymoney.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.domain.Device;
import com.dailymoney.identity.domain.User;
import com.dailymoney.identity.infra.DeviceRepository;
import com.dailymoney.identity.infra.UserRepository;
import com.dailymoney.shared.id.UuidV7;
import com.dailymoney.shared.security.FirebasePrincipal;
import com.dailymoney.shared.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final UserRepository users;
    private final DeviceRepository devices;
    private final FirebaseAccountAdmin firebaseAdmin;
    private final TokenRevocationService revocation;
    private final Clock clock;

    public AccountService(UserRepository users, DeviceRepository devices, FirebaseAccountAdmin firebaseAdmin,
            TokenRevocationService revocation, Clock clock) {
        this.users = users;
        this.devices = devices;
        this.firebaseAdmin = firebaseAdmin;
        this.revocation = revocation;
        this.clock = clock;
    }

    /** First call after every sign-in: creates or refreshes the profile and registers the device. */
    @Transactional
    public User bootstrap(FirebasePrincipal principal, BootstrapCommand cmd) {
        Instant now = clock.instant();
        User user = users.findByFirebaseUid(principal.uid())
                .orElseGet(() -> User.create(UuidV7.next(), principal.uid(), initialName(principal, cmd), now));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DELETED", "This account has been deleted");
        }
        user.updateIdentity(principal.email(), principal.emailVerified(), principal.signInProvider(), now);
        user.updateProfile(cmd.displayName(), cmd.timeZone(), now);
        users.save(user);

        Device device = devices.findById(cmd.deviceId())
                .orElseGet(() -> Device.register(cmd.deviceId(), user.getId(), now));
        device.seen(user.getId(), cmd.deviceName(), cmd.appVersion(), cmd.fcmToken(), now);
        devices.save(device);
        return user;
    }

    @Transactional(readOnly = true)
    public List<Device> devices(FirebasePrincipal principal) {
        return devices.findByUserIdOrderByLastSeenAtDesc(requireUser(principal).getId());
    }

    /** Called at logout so this phone stops receiving push notifications for the account. */
    @Transactional
    public void removeDevice(FirebasePrincipal principal, UUID deviceId) {
        User user = requireUser(principal);
        Device device = devices.findById(deviceId)
                .filter(d -> d.getUserId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("Device not found"));
        device.clearPushToken();
        devices.save(device);
    }

    @Transactional
    public void signOutEverywhere(FirebasePrincipal principal) {
        User user = requireUser(principal);
        firebaseAdmin.revokeRefreshTokens(user.getFirebaseUid());
        user.revokeTokens(clock.instant());
        users.save(user);
        revocation.evict(user.getFirebaseUid());
    }

    /** Spec §22 account deletion. Organization data anonymisation is added with the organization module. */
    @Transactional
    public void deleteAccount(FirebasePrincipal principal) {
        User user = requireUser(principal);
        devices.deleteByUserId(user.getId());
        user.markDeleted(clock.instant());
        users.save(user);
        revocation.evict(user.getFirebaseUid());
        firebaseAdmin.deleteUser(user.getFirebaseUid());
    }

    private User requireUser(FirebasePrincipal principal) {
        return users.findByFirebaseUid(principal.uid())
                .filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_REQUIRED",
                        "Call /me/bootstrap after signing in"));
    }

    private static String initialName(FirebasePrincipal principal, BootstrapCommand cmd) {
        if (cmd.displayName() != null && !cmd.displayName().isBlank()) {
            return cmd.displayName().trim();
        }
        if (principal.name() != null && !principal.name().isBlank()) {
            return principal.name();
        }
        if (principal.email() != null) {
            return principal.email().substring(0, principal.email().indexOf('@'));
        }
        return "User";
    }
}
