package com.dailymoney.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.dailymoney.identity.domain.Device;
import com.dailymoney.identity.domain.User;
import com.dailymoney.identity.infra.DeviceRepository;
import com.dailymoney.identity.infra.UserRepository;
import com.dailymoney.organization.application.OrganizationService;
import com.dailymoney.sync.NotificationService;
import com.dailymoney.shared.id.UuidV7;
import com.dailymoney.shared.security.FirebasePrincipal;
import com.dailymoney.shared.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Pattern UPI_ID = Pattern.compile("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,32}$");

    private final UserRepository users;
    private final DeviceRepository devices;
    private final FirebaseAccountAdmin firebaseAdmin;
    private final TokenRevocationService revocation;
    private final Clock clock;
    private final OrganizationService organizations;
    private final NotificationService notifications;

    public AccountService(UserRepository users, DeviceRepository devices, FirebaseAccountAdmin firebaseAdmin,
            TokenRevocationService revocation, Clock clock, OrganizationService organizations, NotificationService notifications) {
        this.organizations = organizations;
        this.notifications = notifications;
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

    /** Blank clears it. Shared only with people in the same groups, so they can pay through a UPI app. */
    @Transactional
    public User changeUpiId(FirebasePrincipal principal, String upiId) {
        User user = requireUser(principal);
        String cleaned = upiId == null || upiId.isBlank() ? null : upiId.trim();
        if (cleaned != null && !UPI_ID.matcher(cleaned).matches()) {
            throw ApiException.rule("INVALID_UPI_ID", "Enter a UPI ID like name@bank");
        }
        user.changeUpiId(cleaned, clock.instant());
        return users.save(user);
    }

    @Transactional
    public void signOutEverywhere(FirebasePrincipal principal) {
        User user = requireUser(principal);
        firebaseAdmin.revokeRefreshTokens(user.getFirebaseUid());
        user.revokeTokens(clock.instant());
        users.save(user);
        revocation.evict(user.getFirebaseUid());
    }

    /**
     * Spec §22 / analysis G11: leaves every group (owners hand over first), deletes devices and
     * notifications, and anonymises the profile. Group expenses stay for the others, shown as "Former
     * member"; audit rows keep only the id. Personal money data never reached the server.
     */
    @Transactional
    public void deleteAccount(FirebasePrincipal principal) {
        User user = requireUser(principal);
        organizations.leaveAllForDeletion(user);
        notifications.deleteAllFor(user.getId());
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
