package com.dailymoney.audit;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only change log. Runs inside the caller's transaction, so the record and the change commit together. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID actorId, UUID orgId, String entityType, Object entityId, String action, String detail) {
        jdbc.update(
                "INSERT INTO audit_log (actor_id, org_id, entity_type, entity_id, action, detail) VALUES (?, ?, ?, ?, ?, ?)",
                actorId, orgId, entityType, String.valueOf(entityId), action, detail);
    }
}
