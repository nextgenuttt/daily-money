package com.dailymoney.permission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.Map;

import com.dailymoney.support.IntegrationTest;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec §12 and §24 tests 5–6: individual allow/deny overrides and role editing through the real API. */
class PermissionFlowTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String createOrg(TestUser owner) throws Exception {
        return body(mvc.perform(withJson(post("/api/v1/organizations").with(owner.auth()), Map.of("name", "Family", "kind", "FAMILY")))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private void join(TestUser owner, String orgId, TestUser user, String role) throws Exception {
        String token = body(mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(owner.auth()), Map.of("role", role)))
                .andExpect(status().isCreated()).andReturn()).get("token").asString();
        mvc.perform(post("/api/v1/invitations/by-token/" + token + "/accept").with(user.auth())).andExpect(status().isOk());
    }

    private String userId(TestUser u) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE firebase_uid = ?", String.class, u.uid());
    }

    private String roleId(TestUser owner, String orgId, String code) throws Exception {
        JsonNode matrix = body(mvc.perform(get("/api/v1/organizations/" + orgId + "/permissions").with(owner.auth())).andReturn());
        for (JsonNode r : matrix.get("roles")) if (r.get("code").asString().equals(code)) return r.get("id").asString();
        throw new AssertionError("role " + code);
    }

    @Test
    void allowOverrideGrantsAndDenyBeatsRole() throws Exception {
        TestUser owner = newUser("Father");
        TestUser brother = newUser("Brother");
        TestUser mother = newUser("Mother");
        String orgId = createOrg(owner);
        join(owner, orgId, brother, "VIEWER");
        join(owner, orgId, mother, "MEMBER");

        // Viewer + ALLOW MEMBER_INVITE can now invite.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(brother) + "/permissions").with(owner.auth()),
                        Map.of("overrides", Map.of("MEMBER_INVITE", "ALLOW"))))
                .andExpect(status().isNoContent());
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(brother.auth()), Map.of("role", "VIEWER")))
                .andExpect(status().isCreated());

        // Member with DENY on reports loses it although the role allows it.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(mother) + "/permissions").with(owner.auth()),
                        Map.of("overrides", Map.of("EXPENSE_REPORT_VIEW", "DENY"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members/" + userId(mother) + "/permissions").with(owner.auth()))
                .andExpect(jsonPath("$.roleDefaults").value(hasItem("EXPENSE_REPORT_VIEW")))
                .andExpect(jsonPath("$.effective").value(not(hasItem("EXPENSE_REPORT_VIEW"))))
                .andExpect(jsonPath("$.overrides.EXPENSE_REPORT_VIEW").value("DENY"));
        // The member sees the same result on the group itself (cache refreshed by permVersion).
        mvc.perform(get("/api/v1/organizations/" + orgId).with(mother.auth()))
                .andExpect(jsonPath("$.myPermissions").value(not(hasItem("EXPENSE_REPORT_VIEW"))));

        // Clearing overrides returns to the role default.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(mother) + "/permissions").with(owner.auth()), Map.of("overrides", Map.of())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + orgId).with(mother.auth()))
                .andExpect(jsonPath("$.myPermissions").value(hasItem("EXPENSE_REPORT_VIEW")));
    }

    @Test
    void denyOnAdminBlocksInviting() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser admin = newUser("Admin");
        String orgId = createOrg(owner);
        join(owner, orgId, admin, "ADMIN");
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(admin) + "/permissions").with(owner.auth()),
                        Map.of("overrides", Map.of("MEMBER_INVITE", "DENY"))))
                .andExpect(status().isNoContent());
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(admin.auth()), Map.of("role", "MEMBER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void roleEditAppliesToEveryoneWithTheRoleImmediately() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser member = newUser("Member");
        String orgId = createOrg(owner);
        join(owner, orgId, member, "MEMBER");
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(member.auth()), Map.of("role", "VIEWER")))
                .andExpect(status().isForbidden());

        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/roles/" + roleId(owner, orgId, "MEMBER") + "/permissions").with(owner.auth()),
                        Map.of("permissions", List.of("EXPENSE_VIEW", "EXPENSE_ADD", "MEMBER_INVITE"))))
                .andExpect(status().isNoContent());
        mvc.perform(withJson(post("/api/v1/organizations/" + orgId + "/invitations").with(member.auth()), Map.of("role", "VIEWER")))
                .andExpect(status().isCreated());
    }

    @Test
    void guardRails() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser admin = newUser("Admin");
        TestUser member = newUser("Member");
        String orgId = createOrg(owner);
        join(owner, orgId, admin, "ADMIN");
        join(owner, orgId, member, "MEMBER");

        // Owner can turn on approvals for the owner role but can't remove management.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/roles/" + roleId(owner, orgId, "OWNER") + "/permissions").with(owner.auth()),
                        Map.of("permissions", List.of("EXPENSE_VIEW"))))
                .andExpect(jsonPath("$.code").value("OWNER_KEEPS_MANAGEMENT"));

        // Give the admin PERMISSION_MANAGE; the admin still can't edit admins, the owner, themselves, or grant what they lack.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(admin) + "/permissions").with(owner.auth()),
                        Map.of("overrides", Map.of("PERMISSION_MANAGE", "ALLOW"))))
                .andExpect(status().isNoContent());
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/roles/" + roleId(owner, orgId, "ADMIN") + "/permissions").with(admin.auth()),
                        Map.of("permissions", List.of("EXPENSE_VIEW"))))
                .andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(owner) + "/permissions").with(admin.auth()),
                        Map.of("overrides", Map.of("EXPENSE_VIEW", "DENY"))))
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_OWNER"));
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(admin) + "/permissions").with(admin.auth()),
                        Map.of("overrides", Map.of("EXPENSE_APPROVE", "ALLOW"))))
                .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_SELF"));
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/permissions").with(admin.auth()),
                        Map.of("overrides", Map.of("EXPENSE_APPROVE", "ALLOW"))))
                .andExpect(jsonPath("$.code").value("CANNOT_GRANT_UNHELD"));
        // Denying is fine for a permission manager.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/permissions").with(admin.auth()),
                        Map.of("overrides", Map.of("EXPENSE_ADD", "DENY"))))
                .andExpect(status().isNoContent());

        // SMS permissions can't be granted until the feature exists; members can't view others' permissions.
        mvc.perform(withJson(put("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/permissions").with(owner.auth()),
                        Map.of("overrides", Map.of("EXPENSE_SMS_SEND", "ALLOW"))))
                .andExpect(jsonPath("$.code").value("PERMISSION_UNAVAILABLE"));
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members/" + userId(admin) + "/permissions").with(member.auth()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/" + orgId + "/members/" + userId(member) + "/permissions").with(member.auth()))
                .andExpect(status().isOk());
    }

    @Test
    void outsidersGetNotFound() throws Exception {
        TestUser owner = newUser("Owner");
        TestUser stranger = newUser("Stranger");
        String orgId = createOrg(owner);
        mvc.perform(get("/api/v1/organizations/" + orgId + "/permissions").with(stranger.auth())).andExpect(status().isNotFound());
    }
}
