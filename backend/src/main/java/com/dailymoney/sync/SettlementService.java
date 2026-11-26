package com.dailymoney.sync;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.shared.web.ApiException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settling up (LLD §8.3, spec §15). The payer records "I paid" (REQUESTED) and only the receiver can
 * confirm it; the receiver can also record money they got (CONFIRMED at once). A UPI app returning to
 * us is never treated as proof of payment. Confirmed settlements are history and can't be undone.
 */
@Service
public class SettlementService {

    private static final Set<String> METHODS = Set.of("CASH", "UPI", "BANK", "OTHER");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public record NewSettlement(UUID id, UUID payerId, UUID receiverId, Long amountMinor, String currency, String method, String note) {
    }

    private final SyncStore store;
    private final PermissionService permissions;
    private final MembershipRepository memberships;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final Clock clock;

    public SettlementService(SyncStore store, PermissionService permissions, MembershipRepository memberships, AuditService audit,
            ApplicationEventPublisher events, NotificationService notifications, Clock clock) {
        this.store = store;
        this.permissions = permissions;
        this.memberships = memberships;
        this.audit = audit;
        this.events = events;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public Settlement record(User me, UUID orgId, NewSettlement req) {
        permissions.member(orgId, me.getId());
        if (req.id() == null || req.payerId() == null || req.receiverId() == null) throw invalid("Missing details");
        store.lockWrites();
        Settlement existing = store.findSettlementForUpdate(req.id()).orElse(null);
        if (existing != null) {
            // Same id again = the phone retrying after a lost response.
            if (existing.createdBy().equals(me.getId()) && existing.orgId().equals(orgId)) return existing;
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_EXISTS", "This settlement already exists");
        }
        boolean iPaid = req.payerId().equals(me.getId());
        boolean iReceived = req.receiverId().equals(me.getId());
        if (!iPaid && !iReceived) throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You can only record payments you made or received");
        if (req.payerId().equals(req.receiverId())) throw invalid("Choose someone else");
        UUID other = iPaid ? req.receiverId() : req.payerId();
        if (memberships.find(orgId, other).filter(Membership::isActive).isEmpty()) throw invalid("That person isn't in this group");
        if (req.amountMinor() == null || req.amountMinor() <= 0) throw invalid("Amount must be more than zero");
        if (req.currency() == null || !CURRENCY.matcher(req.currency()).matches()) throw invalid("Bad currency");
        if (req.method() == null || !METHODS.contains(req.method())) throw invalid("Choose how it was paid");
        if (req.note() != null && req.note().length() > 200) throw invalid("Note is too long");

        Instant now = clock.instant();
        Settlement s = new Settlement(req.id(), orgId, req.payerId(), req.receiverId(), req.amountMinor(), req.currency(), req.method(),
                iReceived ? Settlement.CONFIRMED : Settlement.REQUESTED, blankToNull(req.note()), me.getId(), now,
                iReceived ? me.getId() : null, iReceived ? now : null, store.nextSeq());
        store.insertSettlement(s);
        audit.record(me.getId(), orgId, "SETTLEMENT", s.id(), "SETTLEMENT_" + s.status(), String.valueOf(s.amountMinor()));
        notify(s, iPaid ? "SETTLEMENT_REQUESTED" : "SETTLEMENT_RECORDED", me);
        events.publishEvent(SyncPoke.users(s.payerId(), s.receiverId()));
        return s;
    }

    @Transactional
    public Settlement confirm(User me, UUID settlementId) {
        Settlement s = requested(me, settlementId);
        if (!s.receiverId().equals(me.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Only the person who received the money can confirm it");
        }
        return decide(me, s, Settlement.CONFIRMED);
    }

    /** The payer withdraws it, or the receiver says the money never arrived. */
    @Transactional
    public Settlement cancel(User me, UUID settlementId) {
        return decide(me, requested(me, settlementId), Settlement.CANCELLED);
    }

    private Settlement requested(User me, UUID id) {
        store.lockWrites();
        Settlement s = store.findSettlementForUpdate(id)
                .filter(x -> x.payerId().equals(me.getId()) || x.receiverId().equals(me.getId()))
                .orElseThrow(() -> ApiException.notFound("Settlement not found"));
        permissions.member(s.orgId(), me.getId());
        if (!Settlement.REQUESTED.equals(s.status())) throw ApiException.rule("ALREADY_DECIDED", "This payment was already " + s.status().toLowerCase());
        return s;
    }

    private Settlement decide(User me, Settlement s, String status) {
        Instant now = clock.instant();
        long seq = store.nextSeq();
        store.decideSettlement(s.id(), status, me.getId(), now, seq);
        audit.record(me.getId(), s.orgId(), "SETTLEMENT", s.id(), "SETTLEMENT_" + status, null);
        notify(s, "SETTLEMENT_" + status, me);
        events.publishEvent(SyncPoke.users(s.payerId(), s.receiverId()));
        return new Settlement(s.id(), s.orgId(), s.payerId(), s.receiverId(), s.amountMinor(), s.currency(), s.method(), status, s.note(),
                s.createdBy(), s.createdAt(), me.getId(), now, seq);
    }

    /**
     * "You owe me": one notification to that member only, at most once a day per pair and group. The
     * amount comes from the asker's phone (balances are worked out there) and is shown as theirs.
     */
    @Transactional
    public void remind(User me, UUID orgId, UUID userId, Long amountMinor, String currency) {
        permissions.member(orgId, me.getId());
        if (userId == null || userId.equals(me.getId())) throw ApiException.rule("INVALID_REMINDER", "Choose someone else");
        if (memberships.find(orgId, userId).filter(Membership::isActive).isEmpty()) throw ApiException.notFound("Member not found");
        if (amountMinor == null || amountMinor <= 0) throw invalid("Amount must be more than zero");
        if (currency == null || !CURRENCY.matcher(currency).matches()) throw invalid("Bad currency");
        store.lockWrites();
        String key = "PAYMENT_REMINDER:" + orgId + ":" + me.getId() + ":" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (notifications.exists(userId, key)) throw ApiException.rule("ALREADY_REMINDED", "You already reminded them today");
        Map<String, Object> params = Map.of("actor", me.getDisplayName(), "amountMinor", amountMinor, "currency", currency);
        notifications.send(Set.of(userId), orgId, "PAYMENT_REMINDER", "MEMBER", me.getId(), params, key);
        audit.record(me.getId(), orgId, "MEMBER", userId, "PAYMENT_REMINDER", String.valueOf(amountMinor));
        events.publishEvent(SyncPoke.users(userId));
    }

    /** Tells the other person: the receiver about a payment to confirm, the payer about the answer. */
    private void notify(Settlement s, String type, User actor) {
        UUID other = actor.getId().equals(s.payerId()) ? s.receiverId() : s.payerId();
        Map<String, Object> params = Map.of("actor", actor.getDisplayName(), "amountMinor", s.amountMinor(), "currency", s.currency());
        notifications.send(Set.of(other), s.orgId(), type, "SETTLEMENT", s.id(), params, type + ":" + s.id());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException invalid(String detail) {
        return ApiException.rule("INVALID_SETTLEMENT", detail);
    }
}
