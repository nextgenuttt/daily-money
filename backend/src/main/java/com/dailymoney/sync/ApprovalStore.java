package com.dailymoney.sync;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.shared.id.UuidV7;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain SQL for approval policies, rules, approvers and decisions; runs in the caller's transaction. */
@Repository
public class ApprovalStore {

    public record Decision(UUID transactionId, int txnVersion, UUID approverId, String decision, String reason, Instant decidedAt) {
    }

    private record RuleParams(Long amountMinor, List<String> categories, List<UUID> userIds) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ApprovalStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public ApprovalPolicy policy(UUID orgId) {
        List<ApprovalPolicy> rows = jdbc.query(
                "SELECT mode, allow_self_approval, required_approvals, reapprove_on_material_change, version FROM org_approval_policies WHERE org_id = ?",
                (rs, i) -> new ApprovalPolicy(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getBoolean(4), rs.getInt(5), List.of(), Set.of()),
                orgId);
        ApprovalPolicy base = rows.isEmpty() ? ApprovalPolicy.instant() : rows.get(0);
        return new ApprovalPolicy(base.mode(), base.allowSelfApproval(), base.requiredApprovals(), base.reapproveOnMaterialChange(),
                base.version(), rules(orgId), approvers(List.of(orgId)).getOrDefault(orgId, Set.of()));
    }

    private List<ApprovalPolicy.Rule> rules(UUID orgId) {
        return jdbc.query("SELECT id, priority, rule_type, params::text, enabled FROM approval_rules WHERE org_id = ? ORDER BY priority",
                (rs, i) -> {
                    RuleParams p = json.readValue(rs.getString(4), RuleParams.class);
                    return new ApprovalPolicy.Rule(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), p.amountMinor(), p.categories(),
                            p.userIds(), rs.getBoolean(5));
                }, orgId);
    }

    public void savePolicy(UUID orgId, ApprovalPolicy p, UUID updatedBy, Instant now) {
        jdbc.update("""
                INSERT INTO org_approval_policies (org_id, mode, allow_self_approval, required_approvals, reapprove_on_material_change, version, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (org_id) DO UPDATE SET mode = EXCLUDED.mode, allow_self_approval = EXCLUDED.allow_self_approval,
                    required_approvals = EXCLUDED.required_approvals, reapprove_on_material_change = EXCLUDED.reapprove_on_material_change,
                    version = org_approval_policies.version + 1, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, orgId, p.mode(), p.allowSelfApproval(), p.requiredApprovals(), p.reapproveOnMaterialChange(), updatedBy, Timestamp.from(now));
        jdbc.update("DELETE FROM approval_rules WHERE org_id = ?", orgId);
        for (ApprovalPolicy.Rule r : p.rules()) {
            jdbc.update("INSERT INTO approval_rules (id, org_id, priority, rule_type, params, enabled) VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                    r.id(), orgId, r.priority(), r.type(), json.writeValueAsString(new RuleParams(r.amountMinor(), r.categories(), r.userIds())), r.enabled());
        }
        jdbc.update("DELETE FROM organization_approvers WHERE org_id = ?", orgId);
        p.approvers().forEach(u -> jdbc.update("INSERT INTO organization_approvers (org_id, user_id) VALUES (?, ?)", orgId, u));
    }

    public Map<UUID, Set<UUID>> approvers(Collection<UUID> orgIds) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        if (orgIds.isEmpty()) return result;
        jdbc.query("SELECT org_id, user_id FROM organization_approvers WHERE org_id = ANY(?)", ps -> uuidArray(ps, 1, orgIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).add(rs.getObject(2, UUID.class));
                });
        return result;
    }

    public void addDecision(UUID transactionId, int version, UUID approverId, String decision, String reason, Instant now) {
        jdbc.update("INSERT INTO expense_approvals (id, transaction_id, txn_version, approver_id, decision, reason, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UuidV7.next(), transactionId, version, approverId, decision, reason, Timestamp.from(now));
    }

    public Map<UUID, List<Decision>> decisions(Collection<UUID> transactionIds) {
        Map<UUID, List<Decision>> result = new HashMap<>();
        if (transactionIds.isEmpty()) return result;
        jdbc.query("SELECT transaction_id, txn_version, approver_id, decision, reason, decided_at FROM expense_approvals "
                + "WHERE transaction_id = ANY(?) ORDER BY decided_at",
                ps -> uuidArray(ps, 1, transactionIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new Decision(rs.getObject(1, UUID.class),
                            rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
                });
        return result;
    }

    /** Pending, not deleted expenses of a group (to re-send or auto-approve when the policy changes). */
    public List<UUID> pendingIn(UUID orgId) {
        return jdbc.queryForList("SELECT id FROM transactions WHERE org_id = ? AND approval_status = 'PENDING_APPROVAL' AND deleted_at IS NULL",
                UUID.class, orgId);
    }

    private static void uuidArray(PreparedStatement ps, int index, Collection<UUID> ids) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("uuid", ids.toArray()));
    }
}
