package com.dailymoney.sync;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.shared.id.UuidV7;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * In-app notifications (LLD §10). Callers hold the sync write lock, decide who may receive what, and
 * wake the phones themselves (one poke per change, not one per notification); this class stores one
 * row per person, deduplicated.
 */
@Service
public class NotificationService {

    public record NotificationView(UUID id, UUID orgId, String type, String entityType, UUID entityId, Map<String, Object> params,
            Instant createdAt, boolean read) {
    }

    public record Row(UUID id, UUID orgId, String type, String entityType, UUID entityId, String params, Instant createdAt,
            Instant readAt, long serverSeq) {
    }

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** [dedupeKey] makes repeats harmless, e.g. "APPROVAL_REQUESTED:{txn}:{version}" fires once per version. */
    public void send(Collection<UUID> users, UUID orgId, String type, String entityType, UUID entityId, Map<String, Object> params, String dedupeKey) {
        if (users.isEmpty()) return;
        String body = json.writeValueAsString(params);
        Timestamp now = Timestamp.from(clock.instant());
        for (UUID user : Set.copyOf(users)) {
            jdbc.update("""
                    INSERT INTO notifications (id, user_id, org_id, type, entity_type, entity_id, params, dedupe_key, created_at, server_seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, nextval('sync_seq'))
                    ON CONFLICT (user_id, dedupe_key) DO NOTHING
                    """, UuidV7.next(), user, orgId, type, entityType, entityId, body, dedupeKey, now);
        }
    }

    /** Marks some (or, with no ids, all) of my notifications read; the sequence moves so my other phones follow. */
    public int markRead(UUID userId, List<UUID> ids) {
        Timestamp now = Timestamp.from(clock.instant());
        if (ids == null || ids.isEmpty()) {
            return jdbc.update("UPDATE notifications SET read_at = ?, server_seq = nextval('sync_seq') WHERE user_id = ? AND read_at IS NULL", now, userId);
        }
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE notifications SET read_at = ?, server_seq = nextval('sync_seq') WHERE user_id = ? AND read_at IS NULL AND id = ANY(?)");
            ps.setTimestamp(1, now);
            ps.setObject(2, userId);
            ps.setArray(3, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        });
    }

    public void deleteAllFor(UUID userId) {
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
    }

    /** Mine, changed after [cursor], from groups I'm still in (or not tied to a group). */
    public List<Row> since(UUID userId, Collection<UUID> orgIds, long cursor, int limit) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT id, org_id, type, entity_type, entity_id, params::text, created_at, read_at, server_seq FROM notifications
                    WHERE user_id = ? AND server_seq > ? AND (org_id IS NULL OR org_id = ANY(?)) ORDER BY server_seq LIMIT ?
                    """);
            ps.setObject(1, userId);
            ps.setLong(2, cursor);
            uuidArray(ps, 3, orgIds);
            ps.setInt(4, limit);
            return ps;
        }, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getLong(9)));
    }

    public NotificationView view(Row r) {
        return new NotificationView(r.id(), r.orgId(), r.type(), r.entityType(), r.entityId(), json.readValue(r.params(), PARAMS),
                r.createdAt(), r.readAt() != null);
    }

    private static void uuidArray(PreparedStatement ps, int index, Collection<UUID> ids) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("uuid", ids.toArray()));
    }
}
