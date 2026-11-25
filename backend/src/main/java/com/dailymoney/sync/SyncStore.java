package com.dailymoney.sync;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain SQL for the sync tables; always called inside the caller's transaction. */
@Repository
public class SyncStore {

    /** Advisory lock key that serializes sync writes (see {@link #lockWrites()}). */
    private static final long WRITE_LOCK = 7_000_007L;

    private static final String COLUMNS = "id, org_id, creator_id, type, amount_minor, currency, category_name, category_icon, occurred_at, "
            + "note, payment_method, visibility, approval_status, version, server_seq, created_at, updated_at, deleted_at";

    public record Revocation(long seq, UUID entityId) {
    }

    public record SplitRow(UUID userId, String method, BigDecimal inputValue, long shareMinor) {
    }

    private static final String SETTLEMENT_COLUMNS = "id, org_id, payer_id, receiver_id, amount_minor, currency, method, status, note, "
            + "created_by, created_at, decided_by, decided_at, server_seq";

    private final JdbcTemplate jdbc;

    public SyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writers take sequence numbers one at a time and commit in that order, so a reader can never
     * see change N+1 while change N is still uncommitted and then skip N forever with its cursor.
     */
    public void lockWrites() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", WRITE_LOCK);
    }

    public long nextSeq() {
        return jdbc.queryForObject("SELECT nextval('sync_seq')", Long.class);
    }

    public Optional<SharedExpense> findForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE id = ? FOR UPDATE", SyncStore::expense, id).stream().findFirst();
    }

    public void insert(SharedExpense e) {
        jdbc.update("INSERT INTO transactions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.orgId(), e.creatorId(), e.type(), e.amountMinor(), e.currency(), e.categoryName(), e.categoryIcon(),
                ts(e.occurredAt()), e.note(), e.paymentMethod(), e.visibility(), e.approvalStatus(), e.version(), e.serverSeq(),
                ts(e.createdAt()), ts(e.updatedAt()), ts(e.deletedAt()));
    }

    public void update(SharedExpense e) {
        jdbc.update("""
                UPDATE transactions SET type = ?, amount_minor = ?, currency = ?, category_name = ?, category_icon = ?, occurred_at = ?,
                       note = ?, payment_method = ?, visibility = ?, approval_status = ?, version = ?, server_seq = ?, updated_at = ?, deleted_at = ?
                WHERE id = ?
                """,
                e.type(), e.amountMinor(), e.currency(), e.categoryName(), e.categoryIcon(), ts(e.occurredAt()), e.note(), e.paymentMethod(),
                e.visibility(), e.approvalStatus(), e.version(), e.serverSeq(), ts(e.updatedAt()), ts(e.deletedAt()), e.id());
    }

    /** Approval decisions change status, not content: the version stays, the change sequence moves on. */
    public void setApprovalStatus(UUID id, String status, long seq, Instant now) {
        jdbc.update("UPDATE transactions SET approval_status = ?, server_seq = ?, updated_at = ? WHERE id = ?", status, seq, ts(now), id);
    }

    public Set<UUID> allowedMembers(UUID transactionId) {
        return new HashSet<>(jdbc.queryForList("SELECT user_id FROM expense_allowed_members WHERE transaction_id = ?", UUID.class, transactionId));
    }

    public Map<UUID, Set<UUID>> allowedMembers(Collection<UUID> transactionIds) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        if (transactionIds.isEmpty()) return result;
        jdbc.query("SELECT transaction_id, user_id FROM expense_allowed_members WHERE transaction_id = ANY(?)",
                ps -> uuidArray(ps, 1, transactionIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).add(rs.getObject(2, UUID.class));
                });
        return result;
    }

    public void replaceAllowedMembers(UUID transactionId, Set<UUID> userIds) {
        jdbc.update("DELETE FROM expense_allowed_members WHERE transaction_id = ?", transactionId);
        userIds.forEach(u -> jdbc.update("INSERT INTO expense_allowed_members (transaction_id, user_id) VALUES (?, ?)", transactionId, u));
    }

    public void addRevocation(long seq, UUID targetUser, String entityType, UUID entityId) {
        jdbc.update("INSERT INTO sync_revocations (seq, target_user, entity_type, entity_id) VALUES (?, ?, ?, ?)", seq, targetUser, entityType, entityId);
    }

    /** Rows of these organizations changed after [cursor], oldest change first. */
    public List<SharedExpense> changedSince(Collection<UUID> orgIds, long cursor, int limit) {
        if (orgIds.isEmpty()) return List.of();
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE org_id = ANY(?) AND server_seq > ? ORDER BY server_seq LIMIT ?",
                ps -> {
                    uuidArray(ps, 1, orgIds);
                    ps.setLong(2, cursor);
                    ps.setInt(3, limit);
                },
                SyncStore::expense);
    }

    public List<Revocation> revocationsSince(UUID userId, long cursor, int limit) {
        return jdbc.query("SELECT seq, entity_id FROM sync_revocations WHERE target_user = ? AND seq > ? ORDER BY seq LIMIT ?",
                (rs, i) -> new Revocation(rs.getLong(1), rs.getObject(2, UUID.class)), userId, cursor, limit);
    }

    // ---- splits

    public List<SplitRow> splits(UUID transactionId) {
        return jdbc.query("SELECT user_id, method, input_value, share_minor FROM expense_splits WHERE transaction_id = ? ORDER BY user_id",
                (rs, i) -> new SplitRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4)), transactionId);
    }

    public Map<UUID, List<SplitRow>> splits(Collection<UUID> transactionIds) {
        Map<UUID, List<SplitRow>> result = new HashMap<>();
        if (transactionIds.isEmpty()) return result;
        jdbc.query("SELECT transaction_id, user_id, method, input_value, share_minor FROM expense_splits WHERE transaction_id = ANY(?) ORDER BY user_id",
                ps -> uuidArray(ps, 1, transactionIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new SplitRow(rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4), rs.getLong(5)));
                });
        return result;
    }

    public void replaceSplits(UUID transactionId, List<SplitRow> rows) {
        jdbc.update("DELETE FROM expense_splits WHERE transaction_id = ?", transactionId);
        rows.forEach(r -> jdbc.update("INSERT INTO expense_splits (transaction_id, user_id, method, input_value, share_minor) VALUES (?, ?, ?, ?, ?)",
                transactionId, r.userId(), r.method(), r.inputValue(), r.shareMinor()));
    }

    // ---- settlements

    public Optional<Settlement> findSettlementForUpdate(UUID id) {
        return jdbc.query("SELECT " + SETTLEMENT_COLUMNS + " FROM settlements WHERE id = ? FOR UPDATE", SyncStore::settlement, id).stream().findFirst();
    }

    public void insertSettlement(Settlement s) {
        jdbc.update("INSERT INTO settlements (" + SETTLEMENT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.id(), s.orgId(), s.payerId(), s.receiverId(), s.amountMinor(), s.currency(), s.method(), s.status(), s.note(),
                s.createdBy(), ts(s.createdAt()), s.decidedBy(), ts(s.decidedAt()), s.serverSeq());
    }

    public void decideSettlement(UUID id, String status, UUID decidedBy, Instant decidedAt, long seq) {
        jdbc.update("UPDATE settlements SET status = ?, decided_by = ?, decided_at = ?, server_seq = ? WHERE id = ?",
                status, decidedBy, ts(decidedAt), seq, id);
    }

    /** Settlements I paid or received in these groups, changed after [cursor]. */
    public List<Settlement> settlementsSince(UUID userId, Collection<UUID> orgIds, long cursor, int limit) {
        if (orgIds.isEmpty()) return List.of();
        return jdbc.query("SELECT " + SETTLEMENT_COLUMNS + " FROM settlements WHERE (payer_id = ? OR receiver_id = ?) AND org_id = ANY(?) "
                + "AND server_seq > ? ORDER BY server_seq LIMIT ?",
                ps -> {
                    ps.setObject(1, userId);
                    ps.setObject(2, userId);
                    uuidArray(ps, 3, orgIds);
                    ps.setLong(4, cursor);
                    ps.setInt(5, limit);
                },
                SyncStore::settlement);
    }

    public Optional<String> rememberedResponse(UUID userId, UUID key) {
        return jdbc.queryForList("SELECT response FROM idempotency_keys WHERE user_id = ? AND key = ?", String.class, userId, key)
                .stream().findFirst();
    }

    public void remember(UUID userId, UUID key, String response) {
        jdbc.update("INSERT INTO idempotency_keys (user_id, key, response) VALUES (?, ?, ?)", userId, key, response);
    }

    public int forgetResponsesBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE created_at < ?", ts(cutoff));
    }

    private static void uuidArray(PreparedStatement ps, int index, Collection<UUID> ids) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("uuid", ids.toArray()));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Settlement settlement(ResultSet rs, int row) throws SQLException {
        return new Settlement(
                rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getObject("payer_id", UUID.class),
                rs.getObject("receiver_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("method"),
                rs.getString("status"), rs.getString("note"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"), rs.getLong("server_seq"));
    }

    private static SharedExpense expense(ResultSet rs, int row) throws SQLException {
        return new SharedExpense(
                rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getObject("creator_id", UUID.class),
                rs.getString("type"), rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("category_name"),
                rs.getString("category_icon"), instant(rs, "occurred_at"), rs.getString("note"), rs.getString("payment_method"),
                rs.getString("visibility"), rs.getString("approval_status"), rs.getInt("version"), rs.getLong("server_seq"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at"));
    }
}
