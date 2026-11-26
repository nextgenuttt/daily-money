package com.dailymoney.core.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGroupsTest {

    @Test
    fun `preview matches the server rule - deny beats role and allow`() {
        val effective = PermissionGroups.effective(
            roleDefaults = setOf("EXPENSE_VIEW", "EXPENSE_ADD", "EXPENSE_REPORT_VIEW"),
            overrides = mapOf("EXPENSE_REPORT_VIEW" to PermissionEffect.DENY, "MEMBER_INVITE" to PermissionEffect.ALLOW),
        )
        assertEquals(setOf("EXPENSE_VIEW", "EXPENSE_ADD", "MEMBER_INVITE"), effective)
    }

    @Test
    fun `every permission appears in exactly one group`() {
        assertEquals(PermissionGroups.ALL.size, PermissionGroups.ALL.toSet().size)
        assertTrue("EXPENSE_EDIT_ALL" !in PermissionGroups.ALL)
    }
}
