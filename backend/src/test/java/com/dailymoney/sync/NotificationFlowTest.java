package com.dailymoney.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dailymoney.support.IntegrationTest;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec §16–17: who is told about what, and expense discussions, through the real API and database. */
class NotificationFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private record Group(String id, TestUser owner, TestUser member, TestUser viewer, TestUser other) {
    }

    private Group group() throws Exception {
        TestUser owner = newUser("Ravi");
        TestUser member = newUser("Asha");
        TestUser viewer = newUser("Vinu");
        TestUser other = newUser("Hari");
        String orgId = body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "Home", "kind", "FAMILY")))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        join(owner, orgId, member, "MEMBER");
        join(owner, orgId, viewer, "VIEWER");
        join(owner, orgId, other, "MEMBER");
        return new Group(orgId, owner, member, viewer, other);
    }

    private void join(TestUser owner, String orgId, TestUser who, String role) throws Exception {
        String token = body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(owner.auth()),
                Map.of("role", role, "maxUses", 1))).andExpect(status().isCreated()).andReturn()).get("token").asString();
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(who.auth())).andExpect(status().isOk());
    }

    private String id(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    private String create(TestUser by, String orgId, long amount, String visibility, List<String> allowed, Map<String, Object> split) throws Exception {
        String txn = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("orgId", orgId);
        p.put("type", "DEBIT");
        p.put("amountMinor", amount);
        p.put("currency", "INR");
        p.put("categoryName", "Groceries");
        p.put("occurredAt", "2026-11-20T10:15:30Z");
        p.put("visibility", visibility);
        p.put("allowedMemberIds", allowed);
        p.put("split", split);
        Map<String, Object> op = new HashMap<>(Map.of("opId", UUID.randomUUID().toString(), "entityType", "TRANSACTION", "entityId", txn,
                "opType", "CREATE", "baseVersion", 0));
        op.put("payload", p);
        mvc.perform(withJson(post("/api/v1/sync/push").with(by.auth()), Map.of("deviceId", UUID.randomUUID().toString(), "ops", List.of(op))))
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"));
        return txn;
    }

    private List<JsonNode> notifications(TestUser user) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        body(mvc.perform(get("/api/v1/sync/pull").with(user.auth())).andReturn()).get("changes").forEach(c -> {
            if (c.get("entityType").asString().equals("NOTIFICATION")) found.add(c.get("notification"));
        });
        return found;
    }

    private List<String> types(TestUser user) throws Exception {
        return notifications(user).stream().map(n -> n.get("type").asString()).toList();
    }

    @Test
    void newExpenseTellsTheAudienceAndSplitMembersGetTheirShare() throws Exception {
        Group g = group();
        create(g.member(), g.id(), 900, "ORGANIZATION", null,
                Map.of("method", "EQUAL", "entries", List.of(Map.of("userId", id(g.owner()), "value", 1), Map.of("userId", id(g.member()), "value", 1))));

        JsonNode share = notifications(g.owner()).get(0);
        assertThat(share.get("type").asString()).isEqualTo("SPLIT_SHARE");
        assertThat(share.get("params").get("shareMinor").asLong()).isEqualTo(450);
        assertThat(share.get("params").get("actor").asString()).isEqualTo("Asha");
        assertThat(types(g.other())).containsExactly("EXPENSE_ADDED");
        assertThat(types(g.viewer())).containsExactly("EXPENSE_ADDED");
        // The creator isn't told about their own expense.
        assertThat(types(g.member())).isEmpty();
    }

    @Test
    void peopleWhoCantSeeAnExpenseAreNotToldAboutIt() throws Exception {
        Group g = group();
        create(g.member(), g.id(), 300, "SELECTED_MEMBERS", List.of(id(g.owner())), null);
        assertThat(types(g.owner())).containsExactly("EXPENSE_ADDED");
        assertThat(types(g.other())).isEmpty();
        assertThat(types(g.viewer())).isEmpty();
    }

    @Test
    void approvalRequestsAndDecisionsReachTheRightPeople() throws Exception {
        Group g = group();
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.owner().auth()),
                Map.of("mode", "REQUIRE_APPROVAL", "approverIds", List.of(id(g.owner())), "requiredApprovals", 1))).andExpect(status().isOk());
        String txn = create(g.member(), g.id(), 300, "ORGANIZATION", null, null);
        assertThat(types(g.owner())).containsExactly("APPROVAL_REQUESTED");
        assertThat(types(g.other())).isEmpty();

        mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/reject").with(g.owner().auth()), Map.of("version", 1, "reason", "No bill")))
                .andExpect(status().isOk());
        JsonNode rejected = notifications(g.member()).get(0);
        assertThat(rejected.get("type").asString()).isEqualTo("EXPENSE_REJECTED");
        assertThat(rejected.get("params").get("reason").asString()).isEqualTo("No bill");
        assertThat(types(g.other())).isEmpty();
    }

    @Test
    void settlementsTellTheOtherPerson() throws Exception {
        Group g = group();
        String sid = UUID.randomUUID().toString();
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/settlements").with(g.member().auth()),
                Map.of("id", sid, "payerId", id(g.member()), "receiverId", id(g.owner()), "amountMinor", 300, "currency", "INR", "method", "UPI")))
                .andExpect(status().isOk());
        assertThat(types(g.owner())).containsExactly("SETTLEMENT_REQUESTED");
        mvc.perform(post("/api/v1/settlements/" + sid + "/confirm").with(g.owner().auth())).andExpect(status().isOk());
        assertThat(types(g.member())).containsExactly("SETTLEMENT_CONFIRMED");
    }

    @Test
    void readStateIsKeptOnTheServer() throws Exception {
        Group g = group();
        create(g.member(), g.id(), 300, "ORGANIZATION", null, null);
        create(g.member(), g.id(), 400, "ORGANIZATION", null, null);
        List<JsonNode> mine = notifications(g.owner());
        assertThat(mine).allMatch(n -> !n.get("read").asBoolean());
        mvc.perform(withJson(put("/api/v1/notifications/read").with(g.owner().auth()), Map.of("ids", List.of(mine.get(0).get("id").asString()))))
                .andExpect(status().isNoContent());
        assertThat(notifications(g.owner()).stream().filter(n -> n.get("read").asBoolean()).count()).isEqualTo(1);
        mvc.perform(withJson(put("/api/v1/notifications/read").with(g.owner().auth()), Map.of())).andExpect(status().isNoContent());
        assertThat(notifications(g.owner())).allMatch(n -> n.get("read").asBoolean());
    }

    @Test
    void expenseDiscussionFollowsVisibilityAndPermissions() throws Exception {
        Group g = group();
        String txn = create(g.member(), g.id(), 300, "SELECTED_MEMBERS", List.of(id(g.owner())), null);
        String msg = UUID.randomUUID().toString();

        mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/messages").with(g.owner().auth()), Map.of("id", msg, "body", " Was this for the party? ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Was this for the party?")).andExpect(jsonPath("$.mine").value(true));
        // Retrying the same message doesn't post twice.
        mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/messages").with(g.owner().auth()), Map.of("id", msg, "body", "Was this for the party?")))
                .andExpect(status().isOk());
        assertThat(types(g.member())).contains("MESSAGE");

        // Someone the expense isn't shared with can't read or write.
        mvc.perform(get("/api/v1/transactions/" + txn + "/messages").with(g.other().auth())).andExpect(status().isNotFound());

        mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/messages").with(g.member().auth()),
                Map.of("id", UUID.randomUUID().toString(), "body", "Yes", "parentId", msg))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/transactions/" + txn + "/messages/read").with(g.member().auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/transactions/" + txn + "/messages").with(g.owner().auth()))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].readBy").value(1))
                .andExpect(jsonPath("$.messages[1].parentId").value(msg));

        mvc.perform(delete("/api/v1/messages/" + msg).with(g.member().auth())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/messages/" + msg).with(g.owner().auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/transactions/" + txn + "/messages").with(g.member().auth()))
                .andExpect(jsonPath("$.messages[0].deleted").value(true))
                .andExpect(jsonPath("$.messages[0].body").doesNotExist());
    }

    @Test
    void viewersCanReadButNotWrite() throws Exception {
        Group g = group();
        String txn = create(g.member(), g.id(), 300, "ORGANIZATION", null, null);
        mvc.perform(get("/api/v1/transactions/" + txn + "/messages").with(g.viewer().auth()))
                .andExpect(jsonPath("$.canSend").value(false));
        mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/messages").with(g.viewer().auth()), Map.of("id", UUID.randomUUID().toString(), "body", "hi")))
                .andExpect(status().isForbidden());
    }
}
