package com.dailymoney.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dailymoney.support.IntegrationTest;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec §24 tests 35 (split totals) and settlements, through the real HTTP API and database. */
class SplitSettlementFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private record Group(String id, TestUser owner, TestUser member, TestUser viewer) {
    }

    private Group group() throws Exception {
        TestUser owner = newUser("Ravi");
        TestUser member = newUser("Asha");
        TestUser viewer = newUser("Vinu");
        String orgId = body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "Trip", "kind", "TRAVEL")))
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

    private String id(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    private Map<String, Object> expense(String orgId, String type, long amount, String visibility, List<String> allowed, Map<String, Object> split) {
        Map<String, Object> p = new HashMap<>();
        p.put("orgId", orgId);
        p.put("type", type);
        p.put("amountMinor", amount);
        p.put("currency", "INR");
        p.put("categoryName", "Food");
        p.put("occurredAt", "2026-11-20T10:15:30Z");
        p.put("visibility", visibility);
        p.put("allowedMemberIds", allowed);
        p.put("split", split);
        return p;
    }

    private Map<String, Object> split(String method, Object... userAndValue) {
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (int i = 0; i < userAndValue.length; i += 2) entries.add(Map.of("userId", userAndValue[i], "value", userAndValue[i + 1]));
        return Map.of("method", method, "entries", entries);
    }

    private JsonNode push(TestUser user, String opType, String entityId, int base, Map<String, Object> payload) throws Exception {
        Map<String, Object> op = new HashMap<>();
        op.put("opId", UUID.randomUUID().toString());
        op.put("entityType", "TRANSACTION");
        op.put("entityId", entityId);
        op.put("opType", opType);
        op.put("baseVersion", base);
        op.put("payload", payload);
        return body(mvc.perform(withJson(post("/api/v1/sync/push").with(user.auth()), Map.of("deviceId", UUID.randomUUID().toString(), "ops", List.of(op))))
                .andExpect(status().isOk()).andReturn()).get("results").get(0);
    }

    private JsonNode pull(TestUser user) throws Exception {
        return body(mvc.perform(get("/api/v1/sync/pull").with(user.auth())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode change(JsonNode pull, String id) {
        for (JsonNode c : pull.get("changes")) if (c.get("id").asString().equals(id)) return c;
        return null;
    }

    @Test
    void equalSplitIsStoredAndSentToEveryoneInIt() throws Exception {
        Group g = group();
        String txn = UUID.randomUUID().toString();
        JsonNode r = push(g.member(), "CREATE", txn, 0,
                expense(g.id(), "DEBIT", 1000, "ORGANIZATION", null, split("EQUAL", id(g.owner()), "1", id(g.member()), "1", id(g.viewer()), "1")));
        assertThat(r.get("status").asString()).isEqualTo("APPLIED");

        JsonNode seen = change(pull(g.viewer()), txn).get("data").get("split");
        assertThat(seen.get("method").asString()).isEqualTo("EQUAL");
        long sum = 0;
        for (JsonNode s : seen.get("shares")) sum += s.get("shareMinor").asLong();
        assertThat(sum).isEqualTo(1000);
        assertThat(seen.get("shares")).hasSize(3);

        JsonNode me = pull(g.owner());
        assertThat(me.get("me").asString()).isEqualTo(id(g.owner()));
        assertThat(me.get("orgs").get(0).get("members")).hasSize(3);
        assertThat(me.get("orgs").get(0).get("canSplit").asBoolean()).isTrue();
    }

    @Test
    void badSplitsAreRefused() throws Exception {
        Group g = group();
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0,
                expense(g.id(), "DEBIT", 1000, "ORGANIZATION", null, split("PERCENT", id(g.owner()), "50", id(g.member()), "40")))
                .get("errorCode").asString()).isEqualTo("INVALID_SPLIT");
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0,
                expense(g.id(), "CREDIT", 1000, "ORGANIZATION", null, split("EQUAL", id(g.owner()), "1", id(g.member()), "1")))
                .get("errorCode").asString()).isEqualTo("SPLIT_EXPENSES_ONLY");
        // The viewer can't see an expense shared only with the owner, so they can't be charged for it.
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0,
                expense(g.id(), "DEBIT", 1000, "SELECTED_MEMBERS", List.of(id(g.owner())), split("EQUAL", id(g.owner()), "1", id(g.viewer()), "1")))
                .get("errorCode").asString()).isEqualTo("SPLIT_MEMBERS");
    }

    @Test
    void splittingNeedsThePermissionButKeepingASplitDoesNot() throws Exception {
        Group g = group();
        String txn = UUID.randomUUID().toString();
        Map<String, Object> split = split("SHARES", id(g.owner()), "2", id(g.member()), "1");
        push(g.member(), "CREATE", txn, 0, expense(g.id(), "DEBIT", 900, "ORGANIZATION", null, split));

        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/members/" + id(g.member()) + "/permissions").with(g.owner().auth()),
                Map.of("overrides", Map.of("EXPENSE_SPLIT_MANAGE", "DENY")))).andExpect(status().isNoContent());

        assertThat(push(g.member(), "UPDATE", txn, 1, expense(g.id(), "DEBIT", 1200, "ORGANIZATION", null, split)).get("status").asString())
                .isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT share_minor FROM expense_splits WHERE transaction_id = ?::uuid AND user_id = ?::uuid",
                Long.class, txn, id(g.owner()))).isEqualTo(800L);
        assertThat(push(g.member(), "UPDATE", txn, 2, expense(g.id(), "DEBIT", 1200, "ORGANIZATION", null, split("EQUAL", id(g.owner()), "1", id(g.member()), "1")))
                .get("errorCode").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0, expense(g.id(), "DEBIT", 100, "ORGANIZATION", null, split))
                .get("errorCode").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void databaseRefusesSplitsThatDontAddUp() throws Exception {
        Group g = group();
        String txn = UUID.randomUUID().toString();
        push(g.member(), "CREATE", txn, 0, expense(g.id(), "DEBIT", 1000, "ORGANIZATION", null, split("EQUAL", id(g.owner()), "1", id(g.member()), "1")));
        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET amount_minor = 999 WHERE id = ?::uuid", txn))
                .isInstanceOf(DataAccessException.class);
    }

    private JsonNode settle(TestUser by, String orgId, String id, TestUser payer, TestUser receiver, long amount) throws Exception {
        return body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/settlements").with(by.auth()),
                Map.of("id", id, "payerId", id(payer), "receiverId", id(receiver), "amountMinor", amount, "currency", "INR", "method", "UPI")))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void payerRecordsReceiverConfirms() throws Exception {
        Group g = group();
        String sid = UUID.randomUUID().toString();
        assertThat(settle(g.member(), g.id(), sid, g.member(), g.owner(), 300).get("status").asString()).isEqualTo("REQUESTED");
        // Retrying after a lost response returns the same settlement.
        assertThat(settle(g.member(), g.id(), sid, g.member(), g.owner(), 300).get("status").asString()).isEqualTo("REQUESTED");

        JsonNode ownerSees = change(pull(g.owner()), sid);
        assertThat(ownerSees.get("entityType").asString()).isEqualTo("SETTLEMENT");
        assertThat(ownerSees.get("settlement").get("amountMinor").asLong()).isEqualTo(300);
        assertThat(change(pull(g.viewer()), sid)).isNull();

        mvc.perform(post("/api/v1/settlements/" + sid + "/confirm").with(g.member().auth()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settlements/" + sid + "/confirm").with(g.owner().auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/v1/settlements/" + sid + "/cancel").with(g.member().auth()))
                .andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
        assertThat(change(pull(g.member()), sid).get("settlement").get("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void receiverRecordingIsConfirmedAndPendingCanBeCancelled() throws Exception {
        Group g = group();
        assertThat(settle(g.owner(), g.id(), UUID.randomUUID().toString(), g.viewer(), g.owner(), 500).get("status").asString()).isEqualTo("CONFIRMED");

        String sid = UUID.randomUUID().toString();
        settle(g.member(), g.id(), sid, g.member(), g.owner(), 200);
        mvc.perform(post("/api/v1/settlements/" + sid + "/cancel").with(g.owner().auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        TestUser stranger = newUser("Stranger");
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/settlements").with(stranger.auth()),
                Map.of("id", UUID.randomUUID().toString(), "payerId", id(stranger), "receiverId", id(g.owner()), "amountMinor", 1, "currency", "INR", "method", "CASH")))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/settlements").with(g.viewer().auth()),
                Map.of("id", UUID.randomUUID().toString(), "payerId", id(g.member()), "receiverId", id(g.owner()), "amountMinor", 1, "currency", "INR", "method", "CASH")))
                .andExpect(status().isForbidden());
    }

    @Test
    void upiIdIsValidatedAndSharedWithGroupMembers() throws Exception {
        Group g = group();
        mvc.perform(withJson(put("/api/v1/me/upi").with(g.owner().auth()), Map.of("upiId", "not a upi id")))
                .andExpect(jsonPath("$.code").value("INVALID_UPI_ID"));
        mvc.perform(withJson(put("/api/v1/me/upi").with(g.owner().auth()), Map.of("upiId", "ravi.k@okbank")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upiId").value("ravi.k@okbank"));
        JsonNode members = pull(g.member()).get("orgs").get(0).get("members");
        String owner = id(g.owner());
        for (JsonNode m : members) {
            if (m.get("userId").asString().equals(owner)) assertThat(m.get("upiId").asString()).isEqualTo("ravi.k@okbank");
        }
        assertThat(pull(g.owner()).get("myUpiId").asString()).isEqualTo("ravi.k@okbank");
    }

    @Test
    void phoneIsNormalisedAndSharedWithGroupMembers() throws Exception {
        Group g = group();
        mvc.perform(withJson(put("/api/v1/me/phone").with(g.member().auth()), Map.of("phone", "12345")))
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        mvc.perform(withJson(put("/api/v1/me/phone").with(g.member().auth()), Map.of("phone", "98765 43210")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+919876543210"));
        String member = id(g.member());
        for (JsonNode m : pull(g.owner()).get("orgs").get(0).get("members")) {
            if (m.get("userId").asString().equals(member)) assertThat(m.get("phone").asString()).isEqualTo("+919876543210");
        }
    }

    @Test
    void paymentReminderReachesOnlyThatMemberOncePerDay() throws Exception {
        Group g = group();
        Map<String, Object> req = Map.of("userId", id(g.member()), "amountMinor", 75000, "currency", "INR");
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/payment-reminders").with(g.owner().auth()), req))
                .andExpect(status().isNoContent());
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/payment-reminders").with(g.owner().auth()), req))
                .andExpect(jsonPath("$.code").value("ALREADY_REMINDED"));

        JsonNode reminder = null;
        for (JsonNode c : pull(g.member()).get("changes")) {
            if (c.has("notification") && !c.get("notification").isNull() && c.get("notification").get("type").asString().equals("PAYMENT_REMINDER")) reminder = c.get("notification");
        }
        assertThat(reminder).isNotNull();
        assertThat(reminder.get("params").get("actor").asString()).isEqualTo("Ravi");
        assertThat(reminder.get("params").get("amountMinor").asLong()).isEqualTo(75000);
        for (JsonNode c : pull(g.viewer()).get("changes")) {
            if (c.has("notification") && !c.get("notification").isNull()) assertThat(c.get("notification").get("type").asString()).isNotEqualTo("PAYMENT_REMINDER");
        }

        TestUser stranger = newUser("Stranger");
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/payment-reminders").with(stranger.auth()), req))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post("/api/v1/organizations/" + g.id() + "/payment-reminders").with(g.owner().auth()),
                Map.of("userId", id(g.owner()), "amountMinor", 100, "currency", "INR")))
                .andExpect(jsonPath("$.code").value("INVALID_REMINDER"));
    }
}
