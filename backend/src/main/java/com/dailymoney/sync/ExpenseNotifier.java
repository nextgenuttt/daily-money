package com.dailymoney.sync;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.infra.OrganizationRepository;
import com.dailymoney.permission.Permission;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.shared.web.ApiException;

import org.springframework.stereotype.Component;

/**
 * Who hears about a group expense (spec §16, LLD §10). Recipients are always people allowed to see it,
 * re-checked here: approvers for requests, split members for their share, the rest of the audience
 * once it's approved, and the creator for decisions. Edits to an approved expense stay quiet.
 */
@Component
public class ExpenseNotifier {

    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
    public static final String EXPENSE_APPROVED = "EXPENSE_APPROVED";
    public static final String EXPENSE_REJECTED = "EXPENSE_REJECTED";
    public static final String EXPENSE_ADDED = "EXPENSE_ADDED";
    public static final String SPLIT_SHARE = "SPLIT_SHARE";

    private final NotificationService notifications;
    private final PermissionService permissions;
    private final OrganizationRepository organizations;
    private final CurrentUserService users;

    public ExpenseNotifier(NotificationService notifications, PermissionService permissions, OrganizationRepository organizations,
            CurrentUserService users) {
        this.notifications = notifications;
        this.permissions = permissions;
        this.organizations = organizations;
        this.users = users;
    }

    /**
     * @param becameVisible  now approved and wasn't before (new, or just approved)
     * @param splitChanged   the split (or the amount it divides) changed in this save
     * @param viewers        who sees it in its new state, before per-person permission checks
     */
    public void changed(SharedExpense e, boolean becameVisible, boolean splitChanged, Set<UUID> viewers, ApprovalPolicy policy,
            List<SyncStore.SplitRow> splits) {
        Map<String, Object> base = base(e);
        if (ApprovalPolicy.PENDING.equals(e.approvalStatus())) {
            Set<UUID> approvers = new HashSet<>(policy.approvers());
            if (!policy.allowSelfApproval()) approvers.remove(e.creatorId());
            notifications.send(allowed(e.orgId(), approvers, Permission.EXPENSE_APPROVE), e.orgId(), APPROVAL_REQUESTED,
                    SyncService.ENTITY_TRANSACTION, e.id(), base, APPROVAL_REQUESTED + ":" + e.id() + ":" + e.version());
            return;
        }
        if (!SharedExpense.APPROVED.equals(e.approvalStatus())) return;

        Set<UUID> inSplit = new HashSet<>();
        if (!splits.isEmpty() && (becameVisible || splitChanged)) {
            for (SyncStore.SplitRow row : splits) {
                if (row.userId().equals(e.creatorId())) continue;
                inSplit.add(row.userId());
                if (!canReceive(e.orgId(), row.userId(), Permission.EXPENSE_NOTIFICATION_RECEIVE)) continue;
                Map<String, Object> params = new HashMap<>(base);
                params.put("shareMinor", row.shareMinor());
                notifications.send(Set.of(row.userId()), e.orgId(), SPLIT_SHARE, SyncService.ENTITY_TRANSACTION, e.id(), params,
                        SPLIT_SHARE + ":" + e.id() + ":" + e.version());
            }
        }
        if (becameVisible) {
            Set<UUID> others = new HashSet<>(viewers);
            others.remove(e.creatorId());
            others.removeAll(inSplit);
            Set<UUID> recipients = allowed(e.orgId(), allowed(e.orgId(), others, Permission.EXPENSE_VIEW), Permission.EXPENSE_NOTIFICATION_RECEIVE);
            notifications.send(recipients, e.orgId(), EXPENSE_ADDED, SyncService.ENTITY_TRANSACTION, e.id(), base, EXPENSE_ADDED + ":" + e.id());
        }
    }

    /** The creator always hears the decision on their own expense. */
    public void decided(SharedExpense e, String status, UUID approverId, String reason) {
        Map<String, Object> params = base(e);
        User approver = users.byIds(List.of(approverId)).get(approverId);
        params.put("approver", approver == null ? "" : approver.getDisplayName());
        if (reason != null) params.put("reason", reason);
        String type = SharedExpense.APPROVED.equals(status) ? EXPENSE_APPROVED : EXPENSE_REJECTED;
        notifications.send(Set.of(e.creatorId()), e.orgId(), type, SyncService.ENTITY_TRANSACTION, e.id(), params,
                type + ":" + e.id() + ":" + e.version());
    }

    private Map<String, Object> base(SharedExpense e) {
        Map<String, Object> params = new HashMap<>();
        User creator = users.byIds(List.of(e.creatorId())).get(e.creatorId());
        params.put("actor", creator == null ? "" : creator.getDisplayName());
        params.put("group", organizations.findById(e.orgId()).map(o -> o.getName()).orElse(""));
        params.put("amountMinor", e.amountMinor());
        params.put("currency", e.currency());
        if (e.categoryName() != null) params.put("category", e.categoryName());
        return params;
    }

    private Set<UUID> allowed(UUID orgId, Set<UUID> users, Permission permission) {
        Set<UUID> result = new HashSet<>();
        for (UUID u : users) if (canReceive(orgId, u, permission)) result.add(u);
        return result;
    }

    private boolean canReceive(UUID orgId, UUID userId, Permission permission) {
        try {
            return permissions.member(orgId, userId).has(permission);
        } catch (ApiException notAMember) {
            return false;
        }
    }
}
