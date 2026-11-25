package com.dailymoney.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.infra.MembershipRepository;
import com.dailymoney.permission.Permission;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.permission.PermissionService.Access;
import com.dailymoney.shared.id.UuidV7;
import com.dailymoney.shared.web.ApiException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configurable expense approval (spec §14, LLD §7). Approval never grants editing rights, and
 * decisions are tied to the expense version, so an edit starts the approvals over.
 */
@Service
public class ApprovalService {

    static final int MAX_RULES = 20;

    public record RuleInput(String type, Long amountMinor, List<String> categories, List<UUID> userIds, Boolean enabled) {
    }

    public record PolicyInput(String mode, Boolean allowSelfApproval, Integer requiredApprovals, Boolean reapproveOnMaterialChange,
            List<UUID> approverIds, List<RuleInput> rules) {
    }

    public record Person(UUID userId, String name) {
    }

    public record PolicyView(String mode, boolean allowSelfApproval, int requiredApprovals, boolean reapproveOnMaterialChange,
            List<UUID> approverIds, List<RuleInput> rules, List<Person> eligibleApprovers, boolean canEdit) {
    }

    public record DecisionResult(UUID transactionId, String approvalStatus, int approvals, int requiredApprovals) {
    }

    private final ApprovalStore approvals;
    private final SyncStore store;
    private final SyncService sync;
    private final PermissionService permissions;
    private final MembershipRepository memberships;
    private final CurrentUserService users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ExpenseNotifier notifier;
    private final Clock clock;

    public ApprovalService(ApprovalStore approvals, SyncStore store, SyncService sync, PermissionService permissions,
            MembershipRepository memberships, CurrentUserService users, AuditService audit, ApplicationEventPublisher events,
            ExpenseNotifier notifier, Clock clock) {
        this.approvals = approvals;
        this.store = store;
        this.sync = sync;
        this.permissions = permissions;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Every member may read the policy, so they know when their expenses will need approval. */
    @Transactional(readOnly = true)
    public PolicyView policy(User me, UUID orgId) {
        Access access = permissions.member(orgId, me.getId());
        ApprovalPolicy p = approvals.policy(orgId);
        List<RuleInput> rules = p.rules().stream()
                .map(r -> new RuleInput(r.type(), r.amountMinor(), r.categories(), r.userIds(), r.enabled())).toList();
        return new PolicyView(p.mode(), p.allowSelfApproval(), p.requiredApprovals(), p.reapproveOnMaterialChange(),
                p.approvers().stream().sorted().toList(), rules, eligibleApprovers(orgId),
                access.has(Permission.ORGANIZATION_SETTINGS_MANAGE));
    }

    @Transactional
    public PolicyView updatePolicy(User me, UUID orgId, PolicyInput in) {
        permissions.require(orgId, me.getId(), Permission.ORGANIZATION_SETTINGS_MANAGE);
        String mode = in.mode() == null ? "" : in.mode();
        if (!Set.of(ApprovalPolicy.INSTANT, ApprovalPolicy.REQUIRE_APPROVAL, ApprovalPolicy.CONDITIONAL).contains(mode)) {
            throw ApiException.rule("INVALID_POLICY", "Choose instant, always or conditional approval");
        }
        Set<UUID> approvers = new HashSet<>(in.approverIds() == null ? List.of() : in.approverIds());
        Set<UUID> eligible = eligibleApprovers(orgId).stream().map(Person::userId).collect(Collectors.toSet());
        if (!eligible.containsAll(approvers)) {
            throw ApiException.rule("APPROVER_NEEDS_PERMISSION", "Approvers must be members allowed to approve expenses");
        }
        int required = in.requiredApprovals() == null ? 1 : in.requiredApprovals();
        if (required < 1 || required > 5) throw ApiException.rule("INVALID_POLICY", "Required approvals must be 1 to 5");
        if (!ApprovalPolicy.INSTANT.equals(mode)) {
            if (approvers.isEmpty()) throw ApiException.rule("NO_APPROVERS", "Choose at least one approver");
            if (required > approvers.size()) throw ApiException.rule("TOO_FEW_APPROVERS", "Not enough approvers for that many approvals");
        }
        List<ApprovalPolicy.Rule> rules = rules(mode, in.rules(), orgId);

        ApprovalPolicy before = approvals.policy(orgId);
        ApprovalPolicy after = new ApprovalPolicy(mode, Boolean.TRUE.equals(in.allowSelfApproval()), required,
                in.reapproveOnMaterialChange() == null || in.reapproveOnMaterialChange(), before.version() + 1, rules, approvers);
        store.lockWrites();
        approvals.savePolicy(orgId, after, me.getId(), clock.instant());
        reconcilePending(orgId, before, after, me);
        audit.record(me.getId(), orgId, "APPROVAL_POLICY", orgId, "POLICY_UPDATE", mode + " approvers=" + approvers.size());
        events.publishEvent(SyncPoke.org(orgId));
        return policy(me, orgId);
    }

    /**
     * Expenses waiting for approval follow the new policy: with approvals turned off they are approved;
     * otherwise approvers who were removed lose them and new approvers receive them.
     */
    private void reconcilePending(UUID orgId, ApprovalPolicy before, ApprovalPolicy after, User me) {
        Instant now = clock.instant();
        for (UUID id : approvals.pendingIn(orgId)) {
            SharedExpense e = store.findForUpdate(id).orElseThrow();
            Set<UUID> allowed = store.allowedMembers(id);
            String status = ApprovalPolicy.INSTANT.equals(after.mode()) ? SharedExpense.APPROVED : ApprovalPolicy.PENDING;
            sync.revokeLost(id, e.creatorId(), sync.viewers(orgId, e.approvalStatus(), e.visibility(), allowed, before.approvers()),
                    sync.viewers(orgId, status, e.visibility(), allowed, after.approvers()));
            store.setApprovalStatus(id, status, store.nextSeq(), now);
            SharedExpense updated = store.findForUpdate(id).orElseThrow();
            if (SharedExpense.APPROVED.equals(status)) {
                audit.record(me.getId(), orgId, SyncService.ENTITY_TRANSACTION, id, "AUTO_APPROVE", null);
                notifier.changed(updated, true, false, sync.viewers(orgId, status, e.visibility(), allowed, after.approvers()), after, store.splits(id));
            } else {
                notifier.changed(updated, false, false, Set.of(), after, List.of());
            }
        }
    }

    private List<ApprovalPolicy.Rule> rules(String mode, List<RuleInput> input, UUID orgId) {
        if (!ApprovalPolicy.CONDITIONAL.equals(mode)) return List.of();
        List<RuleInput> in = input == null ? List.of() : input;
        if (in.isEmpty()) throw ApiException.rule("NO_RULES", "Add at least one condition");
        if (in.size() > MAX_RULES) throw ApiException.rule("INVALID_RULE", "Too many conditions");
        Set<UUID> members = memberships.activeInOrg(orgId).stream().map(Membership::getUserId).collect(Collectors.toSet());
        List<ApprovalPolicy.Rule> rules = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            RuleInput r = in.get(i);
            String type = r.type() == null ? "" : r.type();
            ApprovalPolicy.Rule rule = switch (type) {
                case ApprovalPolicy.Rule.AMOUNT_GTE -> {
                    if (r.amountMinor() == null || r.amountMinor() <= 0) throw ApiException.rule("INVALID_RULE", "Enter an amount");
                    yield new ApprovalPolicy.Rule(UuidV7.next(), i, type, r.amountMinor(), null, null, r.enabled() == null || r.enabled());
                }
                case ApprovalPolicy.Rule.CATEGORY_IN -> {
                    List<String> names = r.categories() == null ? List.of()
                            : r.categories().stream().map(String::trim).filter(c -> !c.isEmpty() && c.length() <= 60).distinct().toList();
                    if (names.isEmpty() || names.size() > 30) throw ApiException.rule("INVALID_RULE", "Choose up to 30 categories");
                    yield new ApprovalPolicy.Rule(UuidV7.next(), i, type, null, names, null, r.enabled() == null || r.enabled());
                }
                case ApprovalPolicy.Rule.CREATOR_IN -> {
                    List<UUID> ids = r.userIds() == null ? List.of() : r.userIds().stream().distinct().toList();
                    if (ids.isEmpty() || !members.containsAll(ids)) throw ApiException.rule("INVALID_RULE", "Choose members of this group");
                    yield new ApprovalPolicy.Rule(UuidV7.next(), i, type, null, null, ids, r.enabled() == null || r.enabled());
                }
                default -> throw ApiException.rule("INVALID_RULE", "Unknown condition " + type);
            };
            rules.add(rule);
        }
        return rules;
    }

    private List<Person> eligibleApprovers(UUID orgId) {
        List<UUID> ids = memberships.activeInOrg(orgId).stream().map(Membership::getUserId)
                .filter(u -> permissions.member(orgId, u).has(Permission.EXPENSE_APPROVE)).toList();
        Map<UUID, User> people = users.byIds(ids);
        return ids.stream().filter(people::containsKey).map(u -> new Person(u, people.get(u).getDisplayName())).toList();
    }

    @Transactional
    public DecisionResult approve(User me, UUID transactionId, int version) {
        return decide(me, transactionId, version, SharedExpense.APPROVED, null);
    }

    @Transactional
    public DecisionResult reject(User me, UUID transactionId, int version, String reason) {
        String cleaned = reason == null ? "" : reason.trim();
        if (cleaned.isEmpty() || cleaned.length() > 300) throw ApiException.rule("REASON_REQUIRED", "Say why it's rejected (up to 300 characters)");
        return decide(me, transactionId, version, ApprovalPolicy.REJECTED, cleaned);
    }

    /** LLD §5.2 canApprove: pending, I'm an active approver with EXPENSE_APPROVE, and self-approval only if allowed. */
    private DecisionResult decide(User me, UUID id, int version, String decision, String reason) {
        store.lockWrites();
        SharedExpense e = store.findForUpdate(id).filter(x -> !x.isDeleted()).orElseThrow(() -> ApiException.notFound("Expense not found"));
        Access access = permissions.member(e.orgId(), me.getId());
        ApprovalPolicy policy = approvals.policy(e.orgId());
        if (!SyncService.isApprover(policy, access, me.getId())) throw ApiException.notFound("Expense not found");
        if (!ApprovalPolicy.PENDING.equals(e.approvalStatus())) throw ApiException.rule("NOT_PENDING", "This expense isn't waiting for approval");
        if (e.version() != version) throw new ApiException(HttpStatus.CONFLICT, "CHANGED", "The expense changed; review it again");
        if (e.creatorId().equals(me.getId()) && !policy.allowSelfApproval()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL", "You can't approve your own expense in this group");
        }
        List<ApprovalStore.Decision> current = approvals.decisions(List.of(id)).getOrDefault(id, List.of()).stream()
                .filter(d -> d.txnVersion() == version).toList();
        if (current.stream().anyMatch(d -> d.approverId().equals(me.getId()))) throw ApiException.rule("ALREADY_DECIDED", "You already decided on this");

        Instant now = clock.instant();
        approvals.addDecision(id, version, me.getId(), decision, reason, now);
        long approvedCount = current.stream().filter(d -> SharedExpense.APPROVED.equals(d.decision())).count()
                + (SharedExpense.APPROVED.equals(decision) ? 1 : 0);
        String status = ApprovalPolicy.REJECTED.equals(decision) ? ApprovalPolicy.REJECTED
                : approvedCount >= policy.requiredApprovals() ? SharedExpense.APPROVED : ApprovalPolicy.PENDING;
        Set<UUID> allowed = store.allowedMembers(id);
        sync.revokeLost(id, e.creatorId(), sync.viewers(e.orgId(), e.approvalStatus(), e.visibility(), allowed, policy.approvers()),
                sync.viewers(e.orgId(), status, e.visibility(), allowed, policy.approvers()));
        // The sequence moves even while still pending, so other approvers see the progress.
        store.setApprovalStatus(id, status, store.nextSeq(), now);
        audit.record(me.getId(), e.orgId(), SyncService.ENTITY_TRANSACTION, id, decision.equals(SharedExpense.APPROVED) ? "APPROVE" : "REJECT", reason);
        if (!ApprovalPolicy.PENDING.equals(status)) {
            SharedExpense updated = store.findForUpdate(id).orElseThrow();
            notifier.decided(updated, status, me.getId(), reason);
            if (SharedExpense.APPROVED.equals(status)) {
                notifier.changed(updated, true, false, sync.viewers(e.orgId(), status, e.visibility(), allowed, policy.approvers()), policy, store.splits(id));
            }
        }
        events.publishEvent(SyncPoke.org(e.orgId()));
        return new DecisionResult(id, status, (int) approvedCount, policy.requiredApprovals());
    }
}
