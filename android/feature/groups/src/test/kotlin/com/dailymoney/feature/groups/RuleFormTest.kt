package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.ApprovalRule
import com.dailymoney.core.domain.sharing.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleFormTest {

    @Test
    fun `switched-on conditions become rules`() {
        val rules = RuleForm(amountOn = true, amountText = "5000", categoriesOn = true, categoriesText = " Travel, Fuel ,travel,, ").toRules("INR")
        assertEquals(
            listOf(ApprovalRule(RuleType.AMOUNT_GTE, amountMinor = 500_000), ApprovalRule(RuleType.CATEGORY_IN, categories = listOf("Travel", "Fuel", "travel"))),
            rules,
        )
    }

    @Test
    fun `an incomplete condition is refused`() {
        assertNull(RuleForm(amountOn = true, amountText = "").toRules("INR"))
        assertNull(RuleForm(categoriesOn = true, categoriesText = " , ").toRules("INR"))
        assertNull(RuleForm(creatorsOn = true).toRules("INR"))
        assertEquals(emptyList<ApprovalRule>(), RuleForm().toRules("INR"))
    }
}
