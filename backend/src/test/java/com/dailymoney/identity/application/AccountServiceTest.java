package com.dailymoney.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.dailymoney.identity.domain.Device;
import com.dailymoney.identity.domain.User;
import com.dailymoney.identity.infra.DeviceRepository;
import com.dailymoney.identity.infra.UserRepository;
import com.dailymoney.organization.application.OrganizationService;
import com.dailymoney.sync.NotificationService;
import com.dailymoney.shared.security.FirebasePrincipal;
import com.dailymoney.shared.web.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.500Z");

    private UserRepository users;
    private DeviceRepository devices;
    private FirebaseAccountAdmin admin;
    private TokenRevocationService revocation;
    private AccountService service;
    private OrganizationService organizations;
    private NotificationService notifications;

    private final FirebasePrincipal principal =
            new FirebasePrincipal("uid-1", "Ravi@Example.com", true, null, "password");

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        devices = mock(DeviceRepository.class);
        admin = mock(FirebaseAccountAdmin.class);
        revocation = mock(TokenRevocationService.class);
        organizations = mock(OrganizationService.class);
        notifications = mock(NotificationService.class);
        service = new AccountService(users, devices, admin, revocation, Clock.fixed(NOW, ZoneOffset.UTC), organizations, notifications);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void bootstrapCreatesUserAndDeviceOnFirstLogin() {
        UUID deviceId = UUID.randomUUID();
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(devices.findById(deviceId)).thenReturn(Optional.empty());

        User user = service.bootstrap(principal, new BootstrapCommand(deviceId, "Pixel 8", "0.1.0", "fcm", "Ravi", null));

        assertThat(user.getFirebaseUid()).isEqualTo("uid-1");
        assertThat(user.getEmail()).isEqualTo("ravi@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Ravi");
        assertThat(user.getAuthProviders()).containsExactly("password");
        verify(devices).save(any(Device.class));
    }

    @Test
    void bootstrapIsIdempotentAndAddsNewProvider() {
        User existing = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        existing.updateIdentity("ravi@example.com", true, "password", NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(existing));
        UUID deviceId = UUID.randomUUID();
        when(devices.findById(deviceId)).thenReturn(Optional.empty());

        FirebasePrincipal google = new FirebasePrincipal("uid-1", "ravi@example.com", true, "Ravi K", "google.com");
        User user = service.bootstrap(google, new BootstrapCommand(deviceId, null, null, null, null, null));

        assertThat(user.getAuthProviders()).containsExactly("password", "google.com");
        assertThat(user.getDisplayName()).isEqualTo("Ravi");
    }

    @Test
    void deletedAccountCannotBootstrap() {
        User deleted = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        deleted.markDeleted(NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.bootstrap(principal,
                new BootstrapCommand(UUID.randomUUID(), null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void signOutEverywhereRevokesAndSetsCutoffInWholeSeconds() {
        User user = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        service.signOutEverywhere(principal);

        verify(admin).revokeRefreshTokens("uid-1");
        verify(revocation).evict("uid-1");
        assertThat(user.getTokensValidAfter()).isEqualTo(Instant.parse("2026-11-22T10:15:30Z"));
    }

    @Test
    void cannotRemoveAnotherUsersDevice() {
        User user = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        Device other = Device.register(UUID.randomUUID(), UUID.randomUUID(), NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        when(devices.findById(other.getId())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.removeDevice(principal, other.getId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deleteAccountAnonymisesAndRemovesFirebaseUser() {
        User user = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        service.deleteAccount(principal);

        assertThat(user.isActive()).isFalse();
        assertThat(user.getEmail()).isNull();
        verify(organizations).leaveAllForDeletion(user);
        verify(notifications).deleteAllFor(user.getId());
        verify(devices).deleteByUserId(user.getId());
        verify(admin).deleteUser("uid-1");
    }

    @Test
    void ownerOfASharedGroupIsStoppedBeforeAnythingIsDeleted() {
        User user = User.create(UUID.randomUUID(), "uid-1", "Ravi", NOW);
        when(users.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "TRANSFER_OWNERSHIP_FIRST", "Club"))
                .when(organizations).leaveAllForDeletion(user);

        assertThatThrownBy(() -> service.deleteAccount(principal)).isInstanceOf(ApiException.class);

        assertThat(user.isActive()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(admin, notifications);
    }
}
