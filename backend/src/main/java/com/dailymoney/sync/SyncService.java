package com.dailymoney.sync;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.domain.Organization;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.organization.infra.OrganizationRepository;
import com.dailymoney.permission.Permission;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.permission.PermissionService.Access;
import com.dailymoney.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Push and pull for shared expenses (LLD §6). Every pushed op runs in its own transaction:
 * idempotency check → authorize → version check → apply → audit → revocations → wake other phones.
 */
@Service
public class SyncService {

    public static final int MAX_OPS = 100;
    public static final int MAX_PULL = 500;
    static final int MAX_SELECTED = 50;
    static final String ENTITY_TRANSACTION = "TRANSACTION";
    static final String ENTITY_SETTLEMENT = "SETTLEMENT";
    static final String ENTITY_NOTIFICATION = "NOTIFICATION";
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> TYPES = Set.of("DEBIT", "CREDIT");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "UPI", "CARD", "BANK_TRANSFER", "WALLET", "OTHER");

    public record SplitEntry(UUID userId, BigDecimal value) {
    }

    /** How the creator wants the cost shared; the server recalculates the shares itself. */
    public record SplitPayload(String method, List<SplitEntry> entries) {
    }

    public record ExpensePayload(UUID orgId, String type, Long amountMinor, String currency, String categoryName, String categoryIcon,
            Instant occurredAt, String note, String paymentMethod, String visibility, List<UUID> allowedMemberIds, SplitPayload split) {
    }

    public record ShareView(UUID userId, BigDecimal value, long shareMinor) {
    }

    public record SplitView(String method, List<ShareView> shares) {
    }

    public record Op(UUID opId, String entityType, UUID entityId, String opType, int baseVersion, ExpensePayload payload) {
    }

    /** One approver's decision; only the creator and the group's approvers receive these. */
    public record DecisionView(UUID approverId, String approverName, String decision, String reason, Instant decidedAt, int version) {
    }

    public record ExpenseView(UUID id, UUID orgId, UUID creatorId, String creatorName, boolean mine, String type, long amountMinor,
            String currency, String categoryName, String categoryIcon, Instant occurredAt, String note, String paymentMethod,
            String visibility, List<UUID> allowedMemberIds, String approvalStatus, int version, Instant updatedAt, SplitView split,
            List<DecisionView> approvals) {
    }

    public record OpResult(UUID opId, String status, Integer version, Long serverSeq, String approvalStatus, String errorCode,
            ExpenseView current) {

        static OpResult applied(UUID opId, SharedExpense e) {
            return new OpResult(opId, "APPLIED", e.version(), e.serverSeq(), e.approvalStatus(), null, null);
        }

        static OpResult conflict(UUID opId, ExpenseView current) {
            return new OpResult(opId, "CONFLICT", null, null, null, null, current);
        }

        static OpResult gone(UUID opId) {
            return new OpResult(opId, "GONE", null, null, null, null, null);
        }

        static OpResult rejected(UUID opId, String code) {
            return new OpResult(opId, "REJECTED", null, null, null, code, null);
        }
    }

    /** UPI ID only when the member chose to share one. */
    public record MemberView(UUID userId, String name, String upiId) {
    }

    public record OrgView(UUID id, String name, String currency, boolean canView, boolean canAdd, boolean canReport, boolean canSplit,
            List<MemberView> members, String approvalMode, int requiredApprovals, boolean isApprover, boolean allowSelfApproval) {
    }

    public record SettlementView(UUID id, UUID orgId, UUID payerId, UUID receiverId, long amountMinor, String currency, String method,
            String status, String note, UUID createdBy, Instant createdAt, Instant decidedAt) {

        static SettlementView of(Settlement s) {
            return new SettlementView(s.id(), s.orgId(), s.payerId(), s.receiverId(), s.amountMinor(), s.currency(), s.method(), s.status(),
                    s.note(), s.createdBy(), s.createdAt(), s.decidedAt());
        }
    }

    public record Change(String entityType, String op, UUID id, ExpenseView data, SettlementView settlement,
            NotificationService.NotificationView notification) {
    }

    public record PullResult(UUID me, String myUpiId, List<OrgView> orgs, List<Change> changes, long nextCursor, boolean hasMore) {
    }

    private final SyncStore store;
    private final ApprovalStore approvals;
    private final PermissionService permissions;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final CurrentUserService users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ExpenseNotifier notifier;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SyncService(SyncStore store, ApprovalStore approvals, PermissionService permissions, MembershipRepository memberships, OrganizationRepository organizations,
            CurrentUserService users, AuditService audit, ApplicationEventPublisher events, ExpenseNotifier notifier,
            NotificationService notifications, ObjectMapper json, PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.approvals = approvals;
        this.permissions = permissions;
        this.memberships = memberships;
        this.organizations = organizations;
        this.users = users;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
        this.notifications = notifications;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---- push

    public List<OpResult> push(User me, UUID deviceId, List<Op> ops) {
        if (ops.size() > MAX_OPS) throw ApiException.rule("TOO_MANY_OPS", "Send at most " + MAX_OPS + " changes at a time");
        return ops.stream().map(op -> pushOne(me, deviceId, op)).toList();
    }

    private OpResult pushOne(User me, UUID deviceId, Op op) {
        try {
            return tx.execute(status -> {
                store.lockWrites();
                var remembered = store.rememberedResponse(me.getId(), op.opId());
                if (remembered.isPresent()) return json.readValue(remembered.get(), OpResult.class);
                OpResult result = apply(me, deviceId, op);
                if ("APPLIED".equals(result.status())) store.remember(me.getId(), op.opId(), json.writeValueAsString(result));
                return result;
            });
        } catch (ApiException e) {
            return OpResult.rejected(op.opId(), e.code());
        }
    }

    private OpResult apply(User me, UUID deviceId, Op op) {
        if (!ENTITY_TRANSACTION.equals(op.entityType())) throw ApiException.rule("UNSUPPORTED_ENTITY", "Only shared expenses sync for now");
        if (op.entityId() == null) throw ApiException.rule("INVALID_EXPENSE", "Missing id");
        SharedExpense existing = store.findForUpdate(op.entityId()).orElse(null);
        return switch (op.opType() == null ? "" : op.opType()) {
            case "CREATE" -> create(me, deviceId, op, existing);
            case "UPDATE" -> update(me, deviceId, op, existing);
            case "DELETE" -> delete(me, deviceId, op, existing);
            default -> throw ApiException.rule("INVALID_OP", "Unknown op " + op.opType());
        };
    }

    private OpResult create(User me, UUID deviceId, Op op, SharedExpense existing) {
        if (existing != null) {
            return existing.isDeleted() ? OpResult.gone(op.opId()) : OpResult.conflict(op.opId(), view(existing, me.getId()));
        }
        ExpensePayload p = validated(op.payload());
        Access access = permissions.require(p.orgId(), me.getId(), Permission.EXPENSE_ADD);
        Set<UUID> allowed = allowedMembers(p, me.getId());
        List<SyncStore.SplitRow> splits = splitRows(p, me.getId(), allowed, access, List.of());
        Instant now = clock.instant();
        SharedExpense draft = new SharedExpense(op.entityId(), p.orgId(), me.getId(), p.type(), p.amountMinor(), p.currency(),
                p.categoryName(), p.categoryIcon(), p.occurredAt(), p.note(), p.paymentMethod(), p.visibility(), SharedExpense.APPROVED,
                1, store.nextSeq(), now, now, null);
        ApprovalPolicy policy = approvals.policy(p.orgId());
        SharedExpense e = withStatus(draft, policy.evaluate(draft));
        store.insert(e);
        store.replaceAllowedMembers(e.id(), allowed);
        store.replaceSplits(e.id(), splits);
        notifier.changed(e, SharedExpense.APPROVED.equals(e.approvalStatus()), !splits.isEmpty(),
                viewers(e.orgId(), e.approvalStatus(), e.visibility(), allowed, policy.approvers()), policy, splits);
        audit.record(me.getId(), e.orgId(), ENTITY_TRANSACTION, e.id(), "CREATE", e.visibility());
        events.publishEvent(SyncPoke.org(e.orgId(), deviceId));
        return OpResult.applied(op.opId(), e);
    }

    private OpResult update(User me, UUID deviceId, Op op, SharedExpense existing) {
        if (existing == null || existing.isDeleted()) return OpResult.gone(op.opId());
        requireCreator(existing, me);
        ExpensePayload p = validated(op.payload());
        if (!existing.orgId().equals(p.orgId())) throw ApiException.rule("ORG_CHANGE_NOT_ALLOWED", "An expense can't move to another group");
        Access access = permissions.require(existing.orgId(), me.getId(), Permission.EXPENSE_EDIT_OWN);
        if (existing.version() != op.baseVersion()) return OpResult.conflict(op.opId(), view(existing, me.getId()));

        Set<UUID> before = store.allowedMembers(existing.id());
        Set<UUID> after = allowedMembers(p, me.getId());
        List<SyncStore.SplitRow> splitsBefore = store.splits(existing.id());
        List<SyncStore.SplitRow> splits = splitRows(p, me.getId(), after, access, splitsBefore);
        ApprovalPolicy policy = approvals.policy(existing.orgId());

        SharedExpense candidate = new SharedExpense(existing.id(), existing.orgId(), existing.creatorId(), p.type(), p.amountMinor(), p.currency(),
                p.categoryName(), p.categoryIcon(), p.occurredAt(), p.note(), p.paymentMethod(), p.visibility(), existing.approvalStatus(),
                existing.version() + 1, 0, existing.createdAt(), clock.instant(), null);
        String status = nextStatus(existing, before, candidate, after, policy);
        revokeLost(existing.id(), existing.creatorId(),
                viewers(existing.orgId(), existing.approvalStatus(), existing.visibility(), before, policy.approvers()),
                viewers(existing.orgId(), status, p.visibility(), after, policy.approvers()));
        SharedExpense e = withStatus(candidate, status, store.nextSeq());
        store.update(e);
        store.replaceAllowedMembers(e.id(), after);
        store.replaceSplits(e.id(), splits);
        boolean splitChanged = !splits.isEmpty() && (!sameInputs(splitsBefore, splits) || existing.amountMinor() != e.amountMinor());
        notifier.changed(e, SharedExpense.APPROVED.equals(status) && !SharedExpense.APPROVED.equals(existing.approvalStatus()), splitChanged,
                viewers(e.orgId(), status, e.visibility(), after, policy.approvers()), policy, splits);
        boolean visibilityChanged = !existing.visibility().equals(e.visibility()) || !before.equals(after);
        audit.record(me.getId(), e.orgId(), ENTITY_TRANSACTION, e.id(), visibilityChanged ? "VISIBILITY" : "UPDATE", e.visibility());
        events.publishEvent(SyncPoke.org(e.orgId(), deviceId));
        return OpResult.applied(op.opId(), e);
    }

    private OpResult delete(User me, UUID deviceId, Op op, SharedExpense existing) {
        if (existing == null) return OpResult.gone(op.opId());
        if (existing.isDeleted()) return OpResult.applied(op.opId(), existing);
        requireCreator(existing, me);
        permissions.require(existing.orgId(), me.getId(), Permission.EXPENSE_DELETE_OWN);
        Instant now = clock.instant();
        SharedExpense e = new SharedExpense(existing.id(), existing.orgId(), existing.creatorId(), existing.type(), existing.amountMinor(),
                existing.currency(), existing.categoryName(), existing.categoryIcon(), existing.occurredAt(), existing.note(),
                existing.paymentMethod(), existing.visibility(), existing.approvalStatus(), existing.version() + 1, store.nextSeq(),
                existing.createdAt(), now, now);
        store.update(e);
        audit.record(me.getId(), e.orgId(), ENTITY_TRANSACTION, e.id(), "DELETE", null);
        events.publishEvent(SyncPoke.org(e.orgId(), deviceId));
        return OpResult.applied(op.opId(), e);
    }

    /**
     * LLD §7: pending or rejected expenses are re-evaluated when edited (resubmitted); an approved one goes
     * back for approval only after a material change, when the policy asks for it and would require approval.
     */
    static String nextStatus(SharedExpense before, Set<UUID> allowedBefore, SharedExpense after, Set<UUID> allowedAfter, ApprovalPolicy policy) {
        String evaluated = policy.evaluate(after);
        if (!SharedExpense.APPROVED.equals(before.approvalStatus())) return evaluated;
        boolean again = policy.reapproveOnMaterialChange() && ApprovalPolicy.PENDING.equals(evaluated)
                && isMaterialChange(before, allowedBefore, after, allowedAfter);
        return again ? ApprovalPolicy.PENDING : SharedExpense.APPROVED;
    }

    static boolean isMaterialChange(SharedExpense a, Set<UUID> allowedA, SharedExpense b, Set<UUID> allowedB) {
        boolean widened = (SharedExpense.SELECTED_MEMBERS.equals(a.visibility()) && SharedExpense.ORGANIZATION.equals(b.visibility()))
                || (SharedExpense.SELECTED_MEMBERS.equals(b.visibility()) && !allowedA.containsAll(allowedB));
        return a.amountMinor() != b.amountMinor()
                || !Objects.equals(lower(a.categoryName()), lower(b.categoryName()))
                || !a.occurredAt().equals(b.occurredAt())
                || widened;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    /** Who can see a row in this state, apart from its creator (before each person's EXPENSE_VIEW check). */
    Set<UUID> viewers(UUID orgId, String status, String visibility, Set<UUID> allowed, Set<UUID> approvers) {
        if (SharedExpense.APPROVED.equals(status)) return audience(orgId, visibility, allowed);
        if (ApprovalPolicy.PENDING.equals(status)) return new HashSet<>(approvers);
        return new HashSet<>();
    }

    /** Tombstones for people who could see the row before and can't now (LLD §6.4). */
    void revokeLost(UUID transactionId, UUID creatorId, Set<UUID> before, Set<UUID> after) {
        Set<UUID> lost = new HashSet<>(before);
        lost.removeAll(after);
        lost.remove(creatorId);
        for (UUID user : lost) store.addRevocation(store.nextSeq(), user, ENTITY_TRANSACTION, transactionId);
    }

    private static SharedExpense withStatus(SharedExpense e, String status) {
        return withStatus(e, status, e.serverSeq());
    }

    private static SharedExpense withStatus(SharedExpense e, String status, long seq) {
        return new SharedExpense(e.id(), e.orgId(), e.creatorId(), e.type(), e.amountMinor(), e.currency(), e.categoryName(), e.categoryIcon(),
                e.occurredAt(), e.note(), e.paymentMethod(), e.visibility(), status, e.version(), seq, e.createdAt(), e.updatedAt(), e.deletedAt());
    }

    /** Only the creator can change an expense; no role or override can change that (spec §13). */
    private static void requireCreator(SharedExpense e, User me) {
        if (!e.creatorId().equals(me.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Only the person who added it can change it");
        }
    }

    static ExpensePayload validated(ExpensePayload p) {
        if (p == null || p.orgId() == null) throw ApiException.rule("INVALID_EXPENSE", "Missing group");
        if (p.type() == null || !TYPES.contains(p.type())) throw ApiException.rule("INVALID_EXPENSE", "Type must be DEBIT or CREDIT");
        if (p.amountMinor() == null || p.amountMinor() <= 0) throw ApiException.rule("INVALID_EXPENSE", "Amount must be positive");
        if (p.currency() == null || !CURRENCY.matcher(p.currency()).matches()) throw ApiException.rule("INVALID_EXPENSE", "Bad currency");
        if (p.occurredAt() == null) throw ApiException.rule("INVALID_EXPENSE", "Missing date");
        if (p.note() != null && p.note().length() > 200) throw ApiException.rule("INVALID_EXPENSE", "Note is too long");
        if (p.categoryName() != null && p.categoryName().length() > 60) throw ApiException.rule("INVALID_EXPENSE", "Category name is too long");
        if (p.categoryIcon() != null && p.categoryIcon().length() > 40) throw ApiException.rule("INVALID_EXPENSE", "Category icon is too long");
        if (p.paymentMethod() != null && !PAYMENT_METHODS.contains(p.paymentMethod())) throw ApiException.rule("INVALID_EXPENSE", "Bad payment method");
        if (!SharedExpense.ORGANIZATION.equals(p.visibility()) && !SharedExpense.SELECTED_MEMBERS.equals(p.visibility())) {
            throw ApiException.rule("INVALID_VISIBILITY", "Share with the whole group or with selected members");
        }
        return p;
    }

    /** Selected members must be active members of the group; the creator always sees their own expense. */
    private Set<UUID> allowedMembers(ExpensePayload p, UUID creatorId) {
        if (!SharedExpense.SELECTED_MEMBERS.equals(p.visibility())) return Set.of();
        Set<UUID> wanted = new LinkedHashSet<>(p.allowedMemberIds() == null ? List.of() : p.allowedMemberIds());
        wanted.remove(creatorId);
        if (wanted.isEmpty()) throw ApiException.rule("SELECT_MEMBERS", "Choose at least one other member");
        if (wanted.size() > MAX_SELECTED) throw ApiException.rule("INVALID_MEMBERS", "Too many members selected");
        Set<UUID> active = activeMemberIds(p.orgId());
        if (!active.containsAll(wanted)) throw ApiException.rule("INVALID_MEMBERS", "Everyone selected must be a member of the group");
        return wanted;
    }

    /**
     * Shares for the payload's split (empty = not split). Everyone in a split must be able to see the
     * expense. Adding or changing a split needs EXPENSE_SPLIT_MANAGE; keeping an existing one as it is doesn't.
     */
    private List<SyncStore.SplitRow> splitRows(ExpensePayload p, UUID creatorId, Set<UUID> allowed, Access access, List<SyncStore.SplitRow> before) {
        SplitPayload split = p.split();
        List<SyncStore.SplitRow> rows = List.of();
        if (split != null) {
            if (!"DEBIT".equals(p.type())) throw ApiException.rule("SPLIT_EXPENSES_ONLY", "Only expenses can be split");
            SplitCalculator.Method method;
            try {
                method = SplitCalculator.Method.valueOf(String.valueOf(split.method()));
            } catch (IllegalArgumentException e) {
                throw ApiException.rule("INVALID_SPLIT", "Unknown split method");
            }
            List<SplitCalculator.Entry> entries = split.entries() == null ? List.of()
                    : split.entries().stream().map(x -> new SplitCalculator.Entry(x.userId(), x.value())).toList();
            List<Long> shares = SplitCalculator.split(p.amountMinor(), method, entries);
            Set<UUID> canSee = audience(p.orgId(), p.visibility(), allowed);
            canSee.add(creatorId);
            if (!canSee.containsAll(entries.stream().map(SplitCalculator.Entry::userId).toList())) {
                throw ApiException.rule("SPLIT_MEMBERS", "Everyone in the split must be a member who can see this expense");
            }
            List<SyncStore.SplitRow> built = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                BigDecimal input = method == SplitCalculator.Method.EQUAL ? null : entries.get(i).value();
                built.add(new SyncStore.SplitRow(entries.get(i).userId(), method.name(), input, shares.get(i)));
            }
            rows = built;
        }
        if (!sameInputs(before, rows) && !access.has(Permission.EXPENSE_SPLIT_MANAGE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "EXPENSE_SPLIT_MANAGE is not granted");
        }
        return rows;
    }

    private static boolean sameInputs(List<SyncStore.SplitRow> a, List<SyncStore.SplitRow> b) {
        if (a.size() != b.size()) return false;
        Map<UUID, SyncStore.SplitRow> byUser = a.stream().collect(Collectors.toMap(SyncStore.SplitRow::userId, r -> r));
        return b.stream().allMatch(r -> {
            SyncStore.SplitRow o = byUser.get(r.userId());
            return o != null && o.method().equals(r.method())
                    && (o.inputValue() == null ? r.inputValue() == null : r.inputValue() != null && o.inputValue().compareTo(r.inputValue()) == 0);
        });
    }

    /** People a row is shared with (before the per-person EXPENSE_VIEW check). */
    private Set<UUID> audience(UUID orgId, String visibility, Set<UUID> allowed) {
        return SharedExpense.ORGANIZATION.equals(visibility) ? activeMemberIds(orgId) : new HashSet<>(allowed);
    }

    private Set<UUID> activeMemberIds(UUID orgId) {
        return memberships.activeInOrg(orgId).stream().map(Membership::getUserId).collect(Collectors.toCollection(HashSet::new));
    }

    // ---- pull

    /**
     * Changes after [cursor] the caller may see, plus REVOKE tombstones for rows they lost. Rows they
     * can't see are skipped (never sent), but the cursor still moves past them.
     */
    public PullResult pull(User me, long cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PULL));
        return tx.execute(status -> {
            Map<UUID, Access> access = new HashMap<>();
            Map<UUID, Set<UUID>> approversByOrg = new HashMap<>();
            List<OrgView> orgs = new ArrayList<>();
            for (Membership m : memberships.activeForUser(me.getId())) {
                Organization org = organizations.findById(m.getOrgId()).filter(o -> !o.isDeleted()).orElse(null);
                if (org == null) continue;
                Access a = permissions.member(org.getId(), me.getId());
                ApprovalPolicy policy = approvals.policy(org.getId());
                access.put(org.getId(), a);
                approversByOrg.put(org.getId(), policy.approvers());
                orgs.add(new OrgView(org.getId(), org.getName(), org.getCurrency(), a.has(Permission.EXPENSE_VIEW),
                        a.has(Permission.EXPENSE_ADD), a.has(Permission.EXPENSE_REPORT_VIEW), a.has(Permission.EXPENSE_SPLIT_MANAGE),
                        members(org.getId()), policy.mode(), policy.requiredApprovals(), isApprover(policy, a, me.getId()), policy.allowSelfApproval()));
            }

            List<SharedExpense> rows = store.changedSince(access.keySet(), cursor, size + 1);
            List<SyncStore.Revocation> revocations = store.revocationsSince(me.getId(), cursor, size + 1);
            List<Settlement> settlements = store.settlementsSince(me.getId(), access.keySet(), cursor, size + 1);
            List<NotificationService.Row> notes = notifications.since(me.getId(), access.keySet(), cursor, size + 1);
            record Entry(long seq, SharedExpense row, SyncStore.Revocation revocation, Settlement settlement, NotificationService.Row note) {
            }
            List<Entry> merged = new ArrayList<>();
            rows.forEach(r -> merged.add(new Entry(r.serverSeq(), r, null, null, null)));
            revocations.forEach(r -> merged.add(new Entry(r.seq(), null, r, null, null)));
            settlements.forEach(r -> merged.add(new Entry(r.serverSeq(), null, null, r, null)));
            notes.forEach(r -> merged.add(new Entry(r.serverSeq(), null, null, null, r)));
            merged.sort(Comparator.comparingLong(Entry::seq));
            List<Entry> page = merged.subList(0, Math.min(size, merged.size()));

            List<SharedExpense> pageRows = page.stream().map(Entry::row).filter(r -> r != null).toList();
            Map<UUID, Set<UUID>> allowed = store.allowedMembers(pageRows.stream()
                    .filter(r -> SharedExpense.SELECTED_MEMBERS.equals(r.visibility())).map(SharedExpense::id).toList());
            Map<UUID, List<SyncStore.SplitRow>> splits = store.splits(pageRows.stream().map(SharedExpense::id).toList());
            Map<UUID, List<ApprovalStore.Decision>> decisions = approvals.decisions(pageRows.stream()
                    .filter(r -> r.creatorId().equals(me.getId()) || approversByOrg.getOrDefault(r.orgId(), Set.of()).contains(me.getId()))
                    .map(SharedExpense::id).toList());
            Set<UUID> people = new HashSet<>(pageRows.stream().map(SharedExpense::creatorId).toList());
            decisions.values().forEach(list -> list.forEach(d -> people.add(d.approverId())));
            Map<UUID, User> creators = users.byIds(people);

            List<Change> changes = new ArrayList<>();
            for (Entry entry : page) {
                if (entry.revocation() != null) {
                    changes.add(new Change(ENTITY_TRANSACTION, "REVOKE", entry.revocation().entityId(), null, null, null));
                    continue;
                }
                if (entry.note() != null) {
                    changes.add(new Change(ENTITY_NOTIFICATION, "UPSERT", entry.note().id(), null, null, notifications.view(entry.note())));
                    continue;
                }
                if (entry.settlement() != null) {
                    changes.add(new Change(ENTITY_SETTLEMENT, "UPSERT", entry.settlement().id(), null, SettlementView.of(entry.settlement()), null));
                    continue;
                }
                SharedExpense row = entry.row();
                Set<UUID> rowAllowed = allowed.getOrDefault(row.id(), Set.of());
                if (!visibleTo(row, rowAllowed, me.getId(), access.get(row.orgId()), approversByOrg.getOrDefault(row.orgId(), Set.of()))) continue;
                if (row.isDeleted()) {
                    changes.add(new Change(ENTITY_TRANSACTION, "DELETE", row.id(), null, null, null));
                } else {
                    User creator = creators.get(row.creatorId());
                    changes.add(new Change(ENTITY_TRANSACTION, "UPSERT", row.id(),
                            view(row, me.getId(), creator == null ? "Former member" : creator.getDisplayName(), rowAllowed,
                                    splits.getOrDefault(row.id(), List.of()), decisionViews(decisions.getOrDefault(row.id(), List.of()), creators)),
                            null, null));
                }
            }
            long next = page.isEmpty() ? cursor : page.get(page.size() - 1).seq();
            return new PullResult(me.getId(), me.getUpiId(), orgs, changes, next, merged.size() > size);
        });
    }

    private List<MemberView> members(UUID orgId) {
        List<Membership> active = memberships.activeInOrg(orgId);
        Map<UUID, User> people = users.byIds(active.stream().map(Membership::getUserId).toList());
        return active.stream()
                .map(m -> people.get(m.getUserId()))
                .filter(u -> u != null)
                .map(u -> new MemberView(u.getId(), u.getDisplayName(), u.getUpiId()))
                .toList();
    }

    /** LLD §5.2 canView, applied to every row before it leaves the server. */
    static boolean visibleTo(SharedExpense e, Set<UUID> allowed, UUID viewer, Access access, Set<UUID> approvers) {
        if (e.creatorId().equals(viewer)) return true;
        if (access == null) return false;
        // Pending: only approvers see it, whatever its visibility (LLD §4.11). Rejected: only the creator.
        if (ApprovalPolicy.PENDING.equals(e.approvalStatus())) return approvers.contains(viewer) && access.has(Permission.EXPENSE_APPROVE);
        if (!SharedExpense.APPROVED.equals(e.approvalStatus()) || !access.has(Permission.EXPENSE_VIEW)) return false;
        return SharedExpense.ORGANIZATION.equals(e.visibility()) || allowed.contains(viewer);
    }

    static boolean isApprover(ApprovalPolicy policy, Access access, UUID userId) {
        return policy.approvers().contains(userId) && access.has(Permission.EXPENSE_APPROVE);
    }

    private static List<DecisionView> decisionViews(List<ApprovalStore.Decision> decisions, Map<UUID, User> people) {
        return decisions.stream().map(d -> {
            User u = people.get(d.approverId());
            return new DecisionView(d.approverId(), u == null ? "Former member" : u.getDisplayName(), d.decision(), d.reason(), d.decidedAt(), d.txnVersion());
        }).toList();
    }

    private ExpenseView view(SharedExpense e, UUID me) {
        User creator = users.byIds(List.of(e.creatorId())).get(e.creatorId());
        return view(e, me, creator == null ? "Former member" : creator.getDisplayName(), store.allowedMembers(e.id()), store.splits(e.id()), List.of());
    }

    private static ExpenseView view(SharedExpense e, UUID me, String creatorName, Set<UUID> allowed, List<SyncStore.SplitRow> splits,
            List<DecisionView> decisions) {
        SplitView split = splits.isEmpty() ? null : new SplitView(splits.get(0).method(),
                splits.stream().map(r -> new ShareView(r.userId(), r.inputValue(), r.shareMinor())).toList());
        return new ExpenseView(e.id(), e.orgId(), e.creatorId(), creatorName, e.creatorId().equals(me), e.type(), e.amountMinor(),
                e.currency(), e.categoryName(), e.categoryIcon(), e.occurredAt(), e.note(), e.paymentMethod(), e.visibility(),
                allowed.stream().sorted().toList(), e.approvalStatus(), e.version(), e.updatedAt(), split, decisions);
    }

    /** Replayed pushes older than a week are treated as new; phones retry long before that. */
    @Scheduled(cron = "0 30 3 * * *")
    public void forgetOldResponses() {
        tx.executeWithoutResult(s -> store.forgetResponsesBefore(clock.instant().minus(Duration.ofDays(7))));
    }
}
