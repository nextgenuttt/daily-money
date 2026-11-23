package com.dailymoney.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.dailymoney.support.IntegrationTest;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec §24 tests 1–8 (organization and permissions) through the real HTTP API and database. */
class OrganizationFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String createOrg(TestUser owner, String name) throws Exception {
        JsonNode org = body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", name, "kind", "FAMILY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andReturn());
        return org.get("id").asString();
    }

    private String invite(TestUser by, String orgId, String role, Object email, int maxUses) throws Exception {
        Map<String, Object> req = new java.util.HashMap<>(Map.of("role", role, "maxUses", maxUses));
        if (email != null) req.put("email", email);
        JsonNode created = body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(by.auth()), req))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(created.get("link").asString()).endsWith("/invite/" + created.get("token").asString());
        return created.get("token").asString();
    }

    private void join(TestUser user, String token) throws Exception {
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(user.auth())).andExpect(status().isOk());
    }

    private String userId(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    @Test
    void ownerCreatesGroupWithDefaultRolesAndAudit() throws Exception {
        TestUser owner = newUser("Ravi");
        String orgId = createOrg(owner, "My Family");

        mvc.perform(get("/api/v1/organizations/" + orgId).with(owner.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(4))
                .andExpect(jsonPath("$.myPermissions").value(org.hamcrest.Matchers.hasItem("PERMISSION_MANAGE")))
                .andExpect(jsonPath("$.currency").value("INR"));
        mvc.perform(get("/api/v1/organizations").with(owner.auth()))
                .andExpect(jsonPath("$[0].name").value("My Family"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE org_id = ?::uuid AND action = 'ORG_CREATE'", Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void outsidersCannotSeeTheGroup() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser stranger = newUser("Stranger");
        String orgId = createOrg(owner, "Private");
        mvc.perform(get("/api/v1/organizations/" + orgId).with(stranger.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members").with(stranger.auth())).andExpect(status().isNotFound());
    }

    @Test
    void linkInviteJoinsAsChosenRoleAndUsesRunOut() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser a = newUser("Asha");
        TestUser b = newUser("Bala");
        String orgId = createOrg(owner, "Friends");
        String token = invite(owner, orgId, "MEMBER", null, 1);

        mvc.perform(get("/api/v1/invitations/by-token/" + token).with(a.auth()))
                .andExpect(jsonPath("$.organizationName").value("Friends"))
                .andExpect(jsonPath("$.invitedBy").value("Owner"))
                .andExpect(jsonPath("$.status").value("VALID"));
        join(a, token);
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(b.auth()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_USED_UP"));
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members").with(owner.auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].role").value("MEMBER"))
                .andExpect(jsonPath("$[1].email").value(a.email()));
        // Members see names but not emails.
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members").with(a.auth()))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void emailBoundInviteOnlyWorksForThatEmail() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser intended = newUser("Intended");
        TestUser other = newUser("Other");
        String orgId = createOrg(owner, "Office");
        String token = invite(owner, orgId, "VIEWER", intended.email(), 1);

        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(other.auth()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITE_EMAIL_MISMATCH"));
        join(intended, token);
    }

    @Test
    void revokedAndExpiredInvitesAreRejected() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser joiner = newUser("Joiner");
        String orgId = createOrg(owner, "Trip");
        String revoked = invite(owner, orgId, "MEMBER", null, 5);
        String invitationId = body(mvc.perform(get("/api/v1/organizations/" + orgId + "/invitations").with(owner.auth())).andReturn()).get(0).get("id").asString();
        mvc.perform(delete("/api/v1/invitations/" + invitationId).with(owner.auth())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/invitations/by-token/" + revoked + "/accept").with(joiner.auth()))
                .andExpect(jsonPath("$.code").value("INVITE_REVOKED"));

        String expired = invite(owner, orgId, "MEMBER", null, 5);
        jdbc.update("UPDATE organization_invitations SET expires_at = now() - interval '1 minute' WHERE org_id = ?::uuid AND status = 'PENDING'", orgId);
        mvc.perform(post("/api/v1/invitations/by-token/" + expired + "/accept").with(joiner.auth()))
                .andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        mvc.perform(get("/api/v1/invitations/by-token/not-a-real-token").with(joiner.auth())).andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotInviteAndMemberCannotManage() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser viewer = newUser("Viewer");
        TestUser member = newUser("Member");
        String orgId = createOrg(owner, "Home");
        join(viewer, invite(owner, orgId, "VIEWER", null, 1));
        join(member, invite(owner, orgId, "MEMBER", null, 1));

        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(viewer.auth()), Map.of("role", "MEMBER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(viewer) + "/role").with(member.auth()), Map.of("role", "MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId).with(member.auth()), Map.of("name", "Hacked", "kind", "OTHER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminLimitsAndOwnerGuardRails() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser admin = newUser("Admin");
        TestUser member = newUser("Member");
        String orgId = createOrg(owner, "Family");
        join(admin, invite(owner, orgId, "ADMIN", null, 1));
        join(member, invite(admin, orgId, "MEMBER", null, 1));

        // Admin can't create admins or touch the owner; owner can't be demoted directly.
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(admin.auth()), Map.of("role", "ADMIN")))
                .andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        mvc.perform(delete("/api/v1/organizations/" + orgId + "/members/" + userId(owner)).with(admin.auth()))
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_OWNER"));
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/role").with(owner.auth()), Map.of("role", "OWNER")))
                .andExpect(jsonPath("$.code").value("USE_TRANSFER"));

        // Admin may change a member's role.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/role").with(admin.auth()), Map.of("role", "VIEWER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void removedMemberLosesAccessAndCanRejoin() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser member = newUser("Member");
        String orgId = createOrg(owner, "Flatmates");
        join(member, invite(owner, orgId, "MEMBER", null, 1));

        mvc.perform(delete("/api/v1/organizations/" + orgId + "/members/" + userId(member)).with(owner.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId).with(member.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/organizations").with(member.auth())).andExpect(jsonPath("$.length()").value(0));

        join(member, invite(owner, orgId, "VIEWER", null, 1));
        mvc.perform(get("/api/v1/organizations/" + orgId).with(member.auth())).andExpect(jsonPath("$.myRole").value("VIEWER"));
    }

    @Test
    void ownerMustTransferBeforeLeaving() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser next = newUser("Next");
        String orgId = createOrg(owner, "Club");
        join(next, invite(owner, orgId, "MEMBER", null, 1));

        mvc.perform(post("/api/v1/organizations/" + orgId + "/leave").with(owner.auth()))
                .andExpect(jsonPath("$.code").value("TRANSFER_OWNERSHIP_FIRST"));
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/transfer-ownership").with(owner.auth()), Map.of("userId", userId(next))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId).with(next.auth())).andExpect(jsonPath("$.myRole").value("OWNER"));
        mvc.perform(post("/api/v1/organizations/" + orgId + "/leave").with(owner.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members").with(next.auth())).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void lastOwnerLeavingDeletesTheGroupAndDeleteIsOwnerOnly() throws Exception {
        TestUser owner = newUser("Solo");
        String solo = createOrg(owner, "Solo");
        mvc.perform(post("/api/v1/organizations/" + solo + "/leave").with(owner.auth())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM organizations WHERE id = ?::uuid", Boolean.class, solo)).isTrue();

        TestUser member = newUser("Member");
        String orgId = createOrg(owner, "Shared");
        join(member, invite(owner, orgId, "MEMBER", null, 1));
        mvc.perform(delete("/api/v1/organizations/" + orgId).with(member.auth())).andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        mvc.perform(delete("/api/v1/organizations/" + orgId).with(owner.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId).with(member.auth())).andExpect(status().isNotFound());
    }

    @Test
    void alreadyMemberAndValidation() throws Exception {
        TestUser owner = newUser("Owner");
        String orgId = createOrg(owner, "G");
        String token = invite(owner, orgId, "MEMBER", null, 3);
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(owner.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "", "kind", "FAMILY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
