package com.dailymoney.sync;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec §14 / §24 approval cases through the real HTTP API and database. */
class ApprovalFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private record Group(String id, TestUser owner, TestUser member, TestUser viewer, TestUser helper) {
    }

    private Group group() throws Exception {
        TestUser owner = newUser("Ravi");
        TestUser member = newUser("Asha");
        TestUser viewer = newUser("Vinu");
        TestUser helper = newUser("Hari");
        String orgId = body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "Office", "kind", "OFFICE")))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        join(owner, orgId, member, "MEMBER");
        join(owner, orgId, viewer, "VIEWER");
        join(owner, orgId, helper, "MEMBER");
        return new Group(orgId, owner, member, viewer, helper);
    }

    private void join(TestUser owner, String orgId, TestUser who, String role) throws Exception {
        String token = body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(owner.auth()),
                Map.of("role", role, "maxUses", 1))).andExpect(status().isCreated()).andReturn()).get("token").asString();
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(who.auth())).andExpect(status().isOk());
    }

    private String id(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    private void policy(Group g, String mode, List<String> approvers, int required, List<Map<String, Object>> rules) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", mode);
        body.put("approverIds", approvers);
        body.put("requiredApprovals", required);
        body.put("rules", rules);
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.owner().auth()), body)).andExpect(status().isOk());
    }

    private Map<String, Object> payload(String orgId, long amount, String category) {
        Map<String, Object> p = new HashMap<>();
        p.put("orgId", orgId);
        p.put("type", "DEBIT");
        p.put("amountMinor", amount);
        p.put("currency", "INR");
        p.put("categoryName", category);
        p.put("occurredAt", "2026-11-20T10:15:30Z");
        p.put("visibility", "ORGANIZATION");
        return p;
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

    private JsonNode pull(TestUser user, long cursor) throws Exception {
        return body(mvc.perform(get("/api/v1/sync/pull").param("cursor", String.valueOf(cursor)).with(user.auth())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode change(JsonNode pull, String id) {
        JsonNode found = null;
        for (JsonNode c : pull.get("changes")) if (c.get("id").asString().equals(id)) found = c;
        return found;
    }

    private org.springframework.test.web.servlet.ResultActions decide(TestUser who, String txn, String action, int version, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("reason", reason);
        return mvc.perform(withJson(post("/api/v1/transactions/" + txn + "/" + action).with(who.auth()), body));
    }

    @Test
    void pendingExpenseIsSeenOnlyByCreatorAndApproversUntilApproved() throws Exception {
        Group g = group();
        policy(g, "REQUIRE_APPROVAL", List.of(id(g.owner())), 1, null);
        String txn = UUID.randomUUID().toString();
        assertThat(push(g.member(), "CREATE", txn, 0, payload(g.id(), 700, "Travel")).get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");

        assertThat(change(pull(g.viewer(), 0), txn)).isNull();
        JsonNode ownerSees = change(pull(g.owner(), 0), txn);
        assertThat(ownerSees.get("data").get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        JsonNode org = pull(g.owner(), 0).get("orgs").get(0);
        assertThat(org.get("isApprover").asBoolean()).isTrue();
        assertThat(org.get("approvalMode").asString()).isEqualTo("REQUIRE_APPROVAL");

        decide(g.member(), txn, "approve", 1, null).andExpect(status().isNotFound());
        decide(g.owner(), txn, "approve", 1, null).andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
        assertThat(change(pull(g.viewer(), 0), txn).get("data").get("approvalStatus").asString()).isEqualTo("APPROVED");
        JsonNode history = change(pull(g.member(), 0), txn).get("data").get("approvals");
        assertThat(history.get(0).get("approverName").asString()).isEqualTo("Ravi");
        assertThat(change(pull(g.viewer(), 0), txn).get("data").get("approvals")).isEmpty();
    }

    @Test
    void rejectionNeedsAReasonAndAnEditResubmits() throws Exception {
        Group g = group();
        policy(g, "REQUIRE_APPROVAL", List.of(id(g.owner())), 1, null);
        String txn = UUID.randomUUID().toString();
        push(g.member(), "CREATE", txn, 0, payload(g.id(), 700, "Travel"));
        long ownerCursor = pull(g.owner(), 0).get("nextCursor").asLong();

        decide(g.owner(), txn, "reject", 1, " ").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        decide(g.owner(), txn, "approve", 2, null).andExpect(jsonPath("$.code").value("CHANGED"));
        decide(g.owner(), txn, "reject", 1, "No receipt").andExpect(jsonPath("$.approvalStatus").value("REJECTED"));
        // The approver no longer sees a rejected expense; the creator sees the reason.
        assertThat(change(pull(g.owner(), ownerCursor), txn).get("op").asString()).isEqualTo("REVOKE");
        assertThat(change(pull(g.member(), 0), txn).get("data").get("approvals").get(0).get("reason").asString()).isEqualTo("No receipt");

        assertThat(push(g.member(), "UPDATE", txn, 1, payload(g.id(), 650, "Travel")).get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        decide(g.owner(), txn, "approve", 2, null).andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
    }

    @Test
    void conditionalRulesAndMaterialChangesNeedApproval() throws Exception {
        Group g = group();
        policy(g, "CONDITIONAL", List.of(id(g.owner())), 1, List.of(
                Map.of("type", "AMOUNT_GTE", "amountMinor", 100_000),
                Map.of("type", "CATEGORY_IN", "categories", List.of("travel"))));
        String small = UUID.randomUUID().toString();
        assertThat(push(g.member(), "CREATE", small, 0, payload(g.id(), 500, "Food")).get("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0, payload(g.id(), 500, "Travel")).get("approvalStatus").asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(push(g.member(), "CREATE", UUID.randomUUID().toString(), 0, payload(g.id(), 150_000, "Food")).get("approvalStatus").asString())
                .isEqualTo("PENDING_APPROVAL");

        long viewerCursor = pull(g.viewer(), 0).get("nextCursor").asLong();
        // A note change is not material; raising the amount past the limit is.
        Map<String, Object> noted = payload(g.id(), 500, "Food");
        noted.put("note", "lunch");
        assertThat(push(g.member(), "UPDATE", small, 1, noted).get("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(push(g.member(), "UPDATE", small, 2, payload(g.id(), 200_000, "Food")).get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        List<String> ops = new java.util.ArrayList<>();
        pull(g.viewer(), viewerCursor).get("changes").forEach(c -> {
            if (c.get("id").asString().equals(small)) ops.add(c.get("op").asString());
        });
        // Pull sends a row once, in its latest state: now pending, so the viewer only gets the removal.
        assertThat(ops).containsExactly("REVOKE");
    }

    @Test
    void selfApprovalOffByDefaultAndSeveralApprovalsCanBeRequired() throws Exception {
        Group g = group();
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/members/" + id(g.helper()) + "/permissions").with(g.owner().auth()),
                Map.of("overrides", Map.of("EXPENSE_APPROVE", "ALLOW")))).andExpect(status().isNoContent());
        policy(g, "REQUIRE_APPROVAL", List.of(id(g.owner()), id(g.helper())), 2, null);

        String mine = UUID.randomUUID().toString();
        push(g.owner(), "CREATE", mine, 0, payload(g.id(), 100, "Food"));
        decide(g.owner(), mine, "approve", 1, null).andExpect(jsonPath("$.code").value("SELF_APPROVAL"));

        String txn = UUID.randomUUID().toString();
        push(g.member(), "CREATE", txn, 0, payload(g.id(), 300, "Food"));
        decide(g.owner(), txn, "approve", 1, null).andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL")).andExpect(jsonPath("$.approvals").value(1));
        decide(g.owner(), txn, "approve", 1, null).andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
        decide(g.helper(), txn, "approve", 1, null).andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
    }

    @Test
    void policyIsValidatedAndTurningApprovalsOffApprovesWhatWasWaiting() throws Exception {
        Group g = group();
        Map<String, Object> bad = new HashMap<>(Map.of("mode", "REQUIRE_APPROVAL", "approverIds", List.of(id(g.member())), "requiredApprovals", 1));
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.owner().auth()), bad))
                .andExpect(jsonPath("$.code").value("APPROVER_NEEDS_PERMISSION"));
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.owner().auth()),
                Map.of("mode", "REQUIRE_APPROVAL", "approverIds", List.of(), "requiredApprovals", 1))).andExpect(jsonPath("$.code").value("NO_APPROVERS"));
        mvc.perform(withJson(put("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.member().auth()),
                Map.of("mode", "INSTANT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/" + g.id() + "/approval-policy").with(g.member().auth()))
                .andExpect(jsonPath("$.mode").value("INSTANT")).andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.eligibleApprovers[0].name").value("Ravi"));

        policy(g, "REQUIRE_APPROVAL", List.of(id(g.owner())), 1, null);
        String txn = UUID.randomUUID().toString();
        push(g.member(), "CREATE", txn, 0, payload(g.id(), 300, "Food"));
        policy(g, "INSTANT", List.of(), 1, null);
        assertThat(change(pull(g.viewer(), 0), txn).get("data").get("approvalStatus").asString()).isEqualTo("APPROVED");
    }
}
