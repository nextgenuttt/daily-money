package com.dailymoney.sync;

import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.shared.security.FirebasePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SyncController {

    public record PushRequest(@NotNull UUID deviceId, @NotNull @Size(max = SyncService.MAX_OPS) List<SyncService.Op> ops) {
    }

    public record PushResponse(List<SyncService.OpResult> results) {
    }

    private final SyncService sync;
    private final SettlementService settlements;
    private final CurrentUserService currentUser;

    public SyncController(SyncService sync, SettlementService settlements, CurrentUserService currentUser) {
        this.sync = sync;
        this.settlements = settlements;
        this.currentUser = currentUser;
    }

    @PostMapping("/sync/push")
    public PushResponse push(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PushRequest req) {
        return new PushResponse(sync.push(currentUser.require(FirebasePrincipal.from(jwt)), req.deviceId(), req.ops()));
    }

    @GetMapping("/sync/pull")
    public SyncService.PullResult pull(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "500") int limit) {
        return sync.pull(currentUser.require(FirebasePrincipal.from(jwt)), cursor, limit);
    }

    @PostMapping("/organizations/{orgId}/settlements")
    public SyncService.SettlementView record(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
            @RequestBody SettlementService.NewSettlement req) {
        return SyncService.SettlementView.of(settlements.record(currentUser.require(FirebasePrincipal.from(jwt)), orgId, req));
    }

    public record ReminderRequest(UUID userId, Long amountMinor, String currency) {
    }

    @PostMapping("/organizations/{orgId}/payment-reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remind(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId, @RequestBody ReminderRequest req) {
        settlements.remind(currentUser.require(FirebasePrincipal.from(jwt)), orgId, req.userId(), req.amountMinor(), req.currency());
    }

    @PostMapping("/settlements/{id}/confirm")
    public SyncService.SettlementView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return SyncService.SettlementView.of(settlements.confirm(currentUser.require(FirebasePrincipal.from(jwt)), id));
    }

    @PostMapping("/settlements/{id}/cancel")
    public SyncService.SettlementView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return SyncService.SettlementView.of(settlements.cancel(currentUser.require(FirebasePrincipal.from(jwt)), id));
    }
}
