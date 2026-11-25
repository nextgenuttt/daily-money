package com.dailymoney.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.audit.AuditService;
import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.organization.infra.OrganizationRepository;
import com.dailymoney.permission.Permission;
import com.dailymoney.permission.PermissionService;
import com.dailymoney.permission.PermissionService.Access;
import com.dailymoney.shared.web.ApiException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discussion on a group expense (spec §16, LLD §4.8). Only people who can see the expense take part:
 * reading needs EXPENSE_MESSAGE_RECEIVE, writing EXPENSE_MESSAGE_SEND, answering EXPENSE_MESSAGE_REPLY.
 */
@Service
public class MessageService {

    static final int MAX_BODY = 2000;
    static final String MESSAGE = "MESSAGE";

    public record NewMessage(UUID id, String body, UUID parentId) {
    }

    public record MessageView(UUID id, UUID senderId, String senderName, boolean mine, UUID parentId, String body, Instant createdAt,
            boolean deleted, int readBy) {
    }

    public record ThreadView(boolean canSend, boolean canReply, List<MessageView> messages) {
    }

    private record Context(SharedExpense expense, Access access) {
    }

    private final JdbcTemplate jdbc;
    private final SyncStore store;
    private final ApprovalStore approvals;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final OrganizationRepository organizations;
    private final CurrentUserService users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MessageService(JdbcTemplate jdbc, SyncStore store, ApprovalStore approvals, PermissionService permissions,
            NotificationService notifications, OrganizationRepository organizations, CurrentUserService users, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.approvals = approvals;
        this.permissions = permissions;
        this.notifications = notifications;
        this.organizations = organizations;
        this.users = users;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ThreadView thread(User me, UUID transactionId) {
        Context c = context(me, transactionId);
        return new ThreadView(c.access().has(Permission.EXPENSE_MESSAGE_SEND), c.access().has(Permission.EXPENSE_MESSAGE_REPLY),
                messages(transactionId, me.getId()));
    }

    @Transactional
    public MessageView post(User me, UUID transactionId, NewMessage in) {
        store.lockWrites();
        Context c = context(me, transactionId);
        if (in.id() == null) throw ApiException.rule("INVALID_MESSAGE", "Missing id");
        String body = in.body() == null ? "" : in.body().trim();
        if (body.isEmpty() || body.length() > MAX_BODY) throw ApiException.rule("INVALID_MESSAGE", "Write 1 to " + MAX_BODY + " characters");
        Permission needed = in.parentId() == null ? Permission.EXPENSE_MESSAGE_SEND : Permission.EXPENSE_MESSAGE_REPLY;
        if (!c.access().has(needed)) throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", needed.name() + " is not granted");

        List<UUID> existing = jdbc.queryForList("SELECT sender_id FROM expense_messages WHERE id = ?", UUID.class, in.id());
        if (!existing.isEmpty()) {
            // The phone retrying after a lost response.
            if (existing.get(0).equals(me.getId())) return find(in.id(), transactionId, me.getId());
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_EXISTS", "This message already exists");
        }
        if (in.parentId() != null) {
            Integer parents = jdbc.queryForObject("SELECT count(*) FROM expense_messages WHERE id = ? AND transaction_id = ?", Integer.class,
                    in.parentId(), transactionId);
            if (parents == null || parents == 0) throw ApiException.rule("INVALID_MESSAGE", "Reply to a message in this thread");
        }
        Instant now = clock.instant();
        jdbc.update("INSERT INTO expense_messages (id, transaction_id, org_id, sender_id, parent_id, body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                in.id(), transactionId, c.expense().orgId(), me.getId(), in.parentId(), body, Timestamp.from(now));
        audit.record(me.getId(), c.expense().orgId(), "MESSAGE", in.id(), "MESSAGE_SEND", null);
        notifyParticipants(c.expense(), me, in.id(), body);
        return find(in.id(), transactionId, me.getId());
    }

    @Transactional
    public void markRead(User me, UUID transactionId) {
        context(me, transactionId);
        jdbc.update("""
                INSERT INTO message_receipts (message_id, user_id, read_at)
                SELECT id, ?, ? FROM expense_messages WHERE transaction_id = ? AND sender_id <> ? AND deleted_at IS NULL
                ON CONFLICT (message_id, user_id) DO NOTHING
                """, me.getId(), Timestamp.from(clock.instant()), transactionId, me.getId());
    }

    /** Only the sender can remove their message; the thread keeps a "deleted" placeholder so replies still make sense. */
    @Transactional
    public void delete(User me, UUID messageId) {
        List<UUID> txn = jdbc.queryForList("SELECT transaction_id FROM expense_messages WHERE id = ? AND sender_id = ? AND deleted_at IS NULL",
                UUID.class, messageId, me.getId());
        if (txn.isEmpty()) throw ApiException.notFound("Message not found");
        context(me, txn.get(0));
        jdbc.update("UPDATE expense_messages SET deleted_at = ? WHERE id = ?", Timestamp.from(clock.instant()), messageId);
    }

    /** Same visibility as the expense itself (LLD §5.2), plus permission to read messages. */
    private Context context(User me, UUID transactionId) {
        SharedExpense e = store.findForUpdate(transactionId).filter(x -> !x.isDeleted()).orElseThrow(() -> ApiException.notFound("Expense not found"));
        Access access = permissions.member(e.orgId(), me.getId());
        if (!canRead(e, me.getId(), access)) throw ApiException.notFound("Expense not found");
        return new Context(e, access);
    }

    private boolean canRead(SharedExpense e, UUID userId, Access access) {
        Set<UUID> approvers = approvals.policy(e.orgId()).approvers();
        return SyncService.visibleTo(e, store.allowedMembers(e.id()), userId, access, approvers) && access.has(Permission.EXPENSE_MESSAGE_RECEIVE);
    }

    /** The people in the conversation: the creator, everyone who wrote, and everyone in the split. */
    private void notifyParticipants(SharedExpense e, User me, UUID messageId, String body) {
        Set<UUID> people = new HashSet<>(jdbc.queryForList("SELECT DISTINCT sender_id FROM expense_messages WHERE transaction_id = ?", UUID.class, e.id()));
        people.add(e.creatorId());
        store.splits(e.id()).forEach(r -> people.add(r.userId()));
        people.remove(me.getId());
        Set<UUID> recipients = new HashSet<>();
        for (UUID u : people) {
            try {
                Access a = permissions.member(e.orgId(), u);
                if (canRead(e, u, a) && a.has(Permission.EXPENSE_NOTIFICATION_RECEIVE)) recipients.add(u);
            } catch (ApiException notAMember) {
                // Left the group.
            }
        }
        Map<String, Object> params = new HashMap<>();
        params.put("actor", me.getDisplayName());
        params.put("group", organizations.findById(e.orgId()).map(o -> o.getName()).orElse(""));
        params.put("preview", body.length() > 80 ? body.substring(0, 80) + "…" : body);
        if (e.categoryName() != null) params.put("category", e.categoryName());
        notifications.send(recipients, e.orgId(), MESSAGE, SyncService.ENTITY_TRANSACTION, e.id(), params, MESSAGE + ":" + messageId);
        if (!recipients.isEmpty()) events.publishEvent(SyncPoke.users(recipients.toArray(UUID[]::new)));
    }

    private MessageView find(UUID id, UUID transactionId, UUID me) {
        return messages(transactionId, me).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private List<MessageView> messages(UUID transactionId, UUID me) {
        record Raw(UUID id, UUID sender, UUID parent, String body, Instant createdAt, boolean deleted, int readBy) {
        }
        List<Raw> raw = jdbc.query("""
                SELECT m.id, m.sender_id, m.parent_id, m.body, m.created_at, m.deleted_at IS NOT NULL,
                       (SELECT count(*) FROM message_receipts r WHERE r.message_id = m.id)
                FROM expense_messages m WHERE m.transaction_id = ? ORDER BY m.created_at
                """, (rs, i) -> new Raw(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getBoolean(6), rs.getInt(7)), transactionId);
        Map<UUID, User> people = users.byIds(raw.stream().map(Raw::sender).distinct().toList());
        return raw.stream().map(r -> {
            User u = people.get(r.sender());
            return new MessageView(r.id(), r.sender(), u == null ? "Former member" : u.getDisplayName(), r.sender().equals(me), r.parent(),
                    r.deleted() ? null : r.body(), r.createdAt(), r.deleted(), r.readBy());
        }).toList();
    }
}
