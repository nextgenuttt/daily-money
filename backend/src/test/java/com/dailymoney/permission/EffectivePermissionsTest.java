package com.dailymoney.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** LLD §5.1 evaluation order, without a database. */
class EffectivePermissionsTest {

    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private MemberPermission o(Permission p, MemberPermission.Effect e) {
        return new MemberPermission(org, user, p, e, user, Instant.EPOCH);
    }

    @Test
    void allowAddsDenyRemovesAndDenyWins() {
        var role = EnumSet.of(Permission.EXPENSE_VIEW, Permission.EXPENSE_ADD);
        var result = PermissionService.effective(role, List.of(
                o(Permission.MEMBER_INVITE, MemberPermission.Effect.ALLOW),
                o(Permission.EXPENSE_ADD, MemberPermission.Effect.DENY)));
        assertThat(result).containsExactlyInAnyOrder(Permission.EXPENSE_VIEW, Permission.MEMBER_INVITE);
    }

    @Test
    void systemDisabledPermissionsAreNeverEffective() {
        var result = PermissionService.effective(EnumSet.of(Permission.EXPENSE_SMS_SEND), List.of(o(Permission.EXPENSE_SMS_NOTIFICATION, MemberPermission.Effect.ALLOW)));
        assertThat(result).isEmpty();
    }

    @Test
    void ownerDefaultsHaveNoEditAllPermissionAndApprovalStartsOff() {
        assertThat(SystemRole.OWNER.defaults()).doesNotContain(Permission.EXPENSE_APPROVE);
        assertThat(EnumSet.allOf(Permission.class).stream().map(Enum::name)).doesNotContain("EXPENSE_EDIT_ALL");
    }
}
