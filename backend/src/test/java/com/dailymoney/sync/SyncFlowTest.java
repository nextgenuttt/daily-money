package com.dailymoney.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dailymoney.support.IntegrationTest;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Spec §24 tests 9–14 (shared expenses, visibility, sync) through the real HTTP API and database. */
class SyncFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PushGateway push;

    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void pushAcceptsEverything() {
        reset(push);
        when(push.sendSyncPoke(anyList())).thenReturn(Set.of());
    }

    // ---- helpers

    private record Group(String id, TestUser owner, TestUser member, TestUser viewer) {
    }

    private Group group() throws Exception {
        TestUser owner = newUser("Ravi");
        TestUser member = newUser("Asha");
        TestUser viewer = newUser("Vinu");
        String orgId = body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "Home", "kind", "FAMILY")))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        join(owner, orgId, member, "MEMBER");
        join(owner, orgId, viewer, "VIEWER");
        return new Group(orgId, owner, member, viewer);
    }

    private void join(TestUser owner, String orgId, TestUser who, String role) throws Exception {
        String token = body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(owner.auth()),
                Map.of("role", role, "maxUses", 1))).andExpect(status().isCreated()).andReturn()).get("token").asString();
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(who.auth())).andExpect(status().isOk());
    }

    private String userId(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    private Map<String, Object> payload(String orgId, long amount, String visibility, List<String> allowed) {
        Map<String, Object> p = new HashMap<>();
        p.put("orgId", orgId);
        p.put("type", "DEBIT");
        p.put("amountMinor", amount);
        p.put("currency", "INR");
        p.put("categoryName", "Groceries");
        p.put("categoryIcon", "cart");
        p.put("occurredAt", "2026-11-20T10:15:30Z");
        p.put("note", "Weekly shop");
        p.put("paymentMethod", "UPI");
        p.put("visibility", visibility);
        p.put("allowedMemberIds", allowed);
        return p;
    }

    private Map<String, Object> op(String opId, String entityId, String opType, int baseVersion, Map<String, Object> payload) {
        Map<String, Object> op = new HashMap<>();
        op.put("opId", opId);
        op.put("entityType", "TRANSACTION");
        op.put("entityId", entityId);
        op.put("opType", opType);
        op.put("baseVersion", baseVersion);
        op.put("payload", payload);
        return op;
    }

    private JsonNode push(TestUser user, Map<String, Object> op) throws Exception {
        JsonNode result = body(mvc.perform(withJson(post("/api/v1/sync/push").with(user.auth()),
                Map.of("deviceId", deviceId.toString(), "ops", List.of(op)))).andExpect(status().isOk()).andReturn());
        return result.get("results").get(0);
    }

    private JsonNode pull(TestUser user, long cursor) throws Exception {
        return body(mvc.perform(get("/api/v1/sync/pull").param("cursor", String.valueOf(cursor)).with(user.auth()))
                .andExpect(status().isOk()).andReturn());
    }

    private List<JsonNode> changesFor(JsonNode pull, String id) {
        List<JsonNode> found = new ArrayList<>();
        pull.get("changes").forEach(c -> {
            if (c.get("id").asString().equals(id)) found.add(c);
        });
        return found;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ---- tests

    @Test
    void groupExpenseReachesEveryMemberButNotOutsiders() throws Exception {
        Group g = group();
        TestUser stranger = newUser("Stranger");
        String id = newId();

        JsonNode applied = push(g.member(), op(newId(), id, "CREATE", 0, payload(g.id(), 45_000, "ORGANIZATION", null)));
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("version").asInt()).isEqualTo(1);

        JsonNode ownerView = changesFor(pull(g.owner(), 0), id).get(0);
        assertThat(ownerView.get("op").asString()).isEqualTo("UPSERT");
        assertThat(ownerView.get("data").get("creatorName").asString()).isEqualTo("Asha");
        assertThat(ownerView.get("data").get("mine").asBoolean()).isFalse();
        assertThat(ownerView.get("data").get("amountMinor").asLong()).isEqualTo(45_000);
        assertThat(changesFor(pull(g.member(), 0), id).get(0).get("data").get("mine").asBoolean()).isTrue();
        assertThat(changesFor(pull(g.viewer(), 0), id)).hasSize(1);

        JsonNode strangerPull = pull(stranger, 0);
        assertThat(strangerPull.get("orgs")).isEmpty();
        assertThat(changesFor(strangerPull, id)).isEmpty();
    }

    @Test
    void addingNeedsPermissionAndMembership() throws Exception {
        Group g = group();
        TestUser stranger = newUser("Stranger");

        assertThat(push(g.viewer(), op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "ORGANIZATION", null)))
                .get("errorCode").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(push(stranger, op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "ORGANIZATION", null)))
                .get("errorCode").asString()).isEqualTo("NOT_FOUND");

        JsonNode orgs = pull(g.viewer(), 0).get("orgs");
        assertThat(orgs.get(0).get("canView").asBoolean()).isTrue();
        assertThat(orgs.get(0).get("canAdd").asBoolean()).isFalse();
    }

    @Test
    void selectedMembersOnlyAndNarrowingSendsRevoke() throws Exception {
        Group g = group();
        String id = newId();
        String ownerId = userId(g.owner());
        String viewerId = userId(g.viewer());

        push(g.member(), op(newId(), id, "CREATE", 0, payload(g.id(), 2_000, "SELECTED_MEMBERS", List.of(ownerId))));
        JsonNode ownerPull = pull(g.owner(), 0);
        assertThat(changesFor(ownerPull, id)).hasSize(1);
        // Holding EXPENSE_VIEW is not enough when only selected people were chosen (LLD §5.2).
        assertThat(changesFor(pull(g.viewer(), 0), id)).isEmpty();

        long ownerCursor = ownerPull.get("nextCursor").asLong();
        long viewerCursor = pull(g.viewer(), 0).get("nextCursor").asLong();
        JsonNode updated = push(g.member(), op(newId(), id, "UPDATE", 1, payload(g.id(), 2_500, "SELECTED_MEMBERS", List.of(viewerId))));
        assertThat(updated.get("version").asInt()).isEqualTo(2);

        List<JsonNode> ownerChanges = changesFor(pull(g.owner(), ownerCursor), id);
        assertThat(ownerChanges).hasSize(1);
        assertThat(ownerChanges.get(0).get("op").asString()).isEqualTo("REVOKE");
        List<JsonNode> viewerChanges = changesFor(pull(g.viewer(), viewerCursor), id);
        assertThat(viewerChanges.get(0).get("op").asString()).isEqualTo("UPSERT");
        assertThat(viewerChanges.get(0).get("data").get("amountMinor").asLong()).isEqualTo(2_500);
    }

    @Test
    void selectedMembersMustBelongToTheGroup() throws Exception {
        Group g = group();
        TestUser stranger = newUser("Stranger");
        assertThat(push(g.member(), op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "SELECTED_MEMBERS", List.of(userId(stranger)))))
                .get("errorCode").asString()).isEqualTo("INVALID_MEMBERS");
        assertThat(push(g.member(), op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "SELECTED_MEMBERS", List.of(userId(g.member())))))
                .get("errorCode").asString()).isEqualTo("SELECT_MEMBERS");
        assertThat(push(g.member(), op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "PERSONAL", null)))
                .get("errorCode").asString()).isEqualTo("INVALID_VISIBILITY");
    }

    @Test
    void replayIsIdempotentAndStaleEditsConflict() throws Exception {
        Group g = group();
        String id = newId();
        String opId = newId();
        Map<String, Object> create = op(opId, id, "CREATE", 0, payload(g.id(), 900, "ORGANIZATION", null));

        JsonNode first = push(g.member(), create);
        JsonNode replay = push(g.member(), create);
        assertThat(replay.get("status").asString()).isEqualTo("APPLIED");
        assertThat(replay.get("serverSeq").asLong()).isEqualTo(first.get("serverSeq").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id = ?::uuid", Integer.class, id)).isEqualTo(1);

        push(g.member(), op(newId(), id, "UPDATE", 1, payload(g.id(), 1_000, "ORGANIZATION", null)));
        JsonNode stale = push(g.member(), op(newId(), id, "UPDATE", 1, payload(g.id(), 1_100, "ORGANIZATION", null)));
        assertThat(stale.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(stale.get("current").get("version").asInt()).isEqualTo(2);
        assertThat(stale.get("current").get("amountMinor").asLong()).isEqualTo(1_000);
    }

    @Test
    void onlyTheCreatorCanEditOrDeleteAndDeletesReachOthers() throws Exception {
        Group g = group();
        String id = newId();
        push(g.member(), op(newId(), id, "CREATE", 0, payload(g.id(), 700, "ORGANIZATION", null)));
        long ownerCursor = pull(g.owner(), 0).get("nextCursor").asLong();

        // Even the owner can't change someone else's expense (spec §13).
        assertThat(push(g.owner(), op(newId(), id, "UPDATE", 1, payload(g.id(), 1, "ORGANIZATION", null)))
                .get("errorCode").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(push(g.owner(), op(newId(), id, "DELETE", 1, null)).get("errorCode").asString()).isEqualTo("PERMISSION_DENIED");

        assertThat(push(g.member(), op(newId(), id, "DELETE", 1, null)).get("status").asString()).isEqualTo("APPLIED");
        List<JsonNode> ownerChanges = changesFor(pull(g.owner(), ownerCursor), id);
        assertThat(ownerChanges.get(0).get("op").asString()).isEqualTo("DELETE");
        assertThat(push(g.member(), op(newId(), id, "UPDATE", 2, payload(g.id(), 5, "ORGANIZATION", null)))
                .get("status").asString()).isEqualTo("GONE");
    }

    @Test
    void removedMemberNoLongerGetsTheGroup() throws Exception {
        Group g = group();
        assertThat(pull(g.member(), 0).get("orgs")).hasSize(1);
        mvc.perform(delete("/api/v1/organizations/" + g.id() + "/members/" + userId(g.member())).with(g.owner().auth()))
                .andExpect(status().isNoContent());
        assertThat(pull(g.member(), 0).get("orgs")).isEmpty();
    }

    @Test
    void pullPagesThroughChangesInOrder() throws Exception {
        Group g = group();
        for (int i = 1; i <= 3; i++) {
            push(g.member(), op(newId(), newId(), "CREATE", 0, payload(g.id(), i * 100L, "ORGANIZATION", null)));
        }
        // Three expenses and the owner's three "expense added" notifications = six changes.
        JsonNode page1 = body(mvc.perform(get("/api/v1/sync/pull").param("cursor", "0").param("limit", "4").with(g.owner().auth()))
                .andExpect(status().isOk()).andReturn());
        assertThat(page1.get("changes")).hasSize(4);
        assertThat(page1.get("hasMore").asBoolean()).isTrue();
        JsonNode page2 = pull(g.owner(), page1.get("nextCursor").asLong());
        assertThat(page2.get("changes")).hasSize(2);
        assertThat(page2.get("hasMore").asBoolean()).isFalse();
        JsonNode last = page2.get("changes").get(0).get("entityType").asString().equals("TRANSACTION") ? page2.get("changes").get(0) : page2.get("changes").get(1);
        assertThat(last.get("data").get("amountMinor").asLong()).isEqualTo(300);
    }

    @Test
    void otherMembersPhonesAreWokenButNotTheSender() throws Exception {
        Group g = group();
        String ownerToken = "token-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/me/bootstrap").with(g.owner().auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"displayName\":\"Ravi\",\"fcmToken\":\"" + ownerToken + "\"}"))
                .andExpect(status().isOk());
        String senderToken = "token-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/me/bootstrap").with(g.member().auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"displayName\":\"Asha\",\"fcmToken\":\"" + senderToken + "\"}"))
                .andExpect(status().isOk());

        push(g.member(), op(newId(), newId(), "CREATE", 0, payload(g.id(), 100, "ORGANIZATION", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tokens = ArgumentCaptor.forClass(List.class);
        verify(push, timeout(5_000)).sendSyncPoke(tokens.capture());
        assertThat(tokens.getValue()).contains(ownerToken).doesNotContain(senderToken);
    }
}
