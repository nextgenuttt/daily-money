package com.dailymoney.feature.money

import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.SharedGroup
import com.dailymoney.core.domain.sharing.SplitMethod
import com.dailymoney.core.domain.sharing.Visibility
import com.dailymoney.feature.money.edit.SplitForm
import com.dailymoney.feature.money.edit.TransactionForm
import com.dailymoney.feature.money.edit.buildSplit
import com.dailymoney.feature.money.edit.splitCandidates
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitFormTest {

    private val group = SharedGroup(
        "g", "Trip", "INR", canView = true, canAdd = true, canReport = true, canSplit = true,
        members = listOf(GroupPerson("asha", "Asha", null, false), GroupPerson("me", "Me", null, true), GroupPerson("vinu", "Vinu", null, false)),
    )

    private fun form(visibility: Visibility = Visibility.ORGANIZATION, selected: Set<String> = emptySet(), split: SplitForm, type: TransactionType = TransactionType.EXPENSE) =
        TransactionForm(type = type, amountText = "300", date = LocalDate.of(2026, 11, 20), shareGroupId = "g", shareVisibility = visibility, shareMembers = selected, split = split)

    @Test
    fun `only people who can see the expense can be in the split, me first`() {
        assertEquals(listOf("me", "asha", "vinu"), splitCandidates(group, form(split = SplitForm())).map { it.userId })
        assertEquals(listOf("me", "vinu"), splitCandidates(group, form(Visibility.SELECTED_MEMBERS, setOf("vinu"), SplitForm())).map { it.userId })
    }

    @Test
    fun `amounts are typed in rupees and sent in paise`() {
        val f = form(split = SplitForm(on = true, method = SplitMethod.AMOUNT, people = setOf("me", "asha"), values = mapOf("me" to "250.50", "asha" to "49.5")))
        val spec = buildSplit(f, splitCandidates(group, f), "INR")!!
        assertEquals(listOf("me" to BigDecimal.valueOf(25050), "asha" to BigDecimal.valueOf(4950)), spec.entries.map { it.userId to it.value })
    }

    @Test
    fun `no split when it's off or not an expense`() {
        assertNull(buildSplit(form(split = SplitForm()), emptyList(), "INR"))
        val income = form(split = SplitForm(on = true, people = setOf("me")), type = TransactionType.INCOME)
        assertNull(buildSplit(income, splitCandidates(group, income), "INR"))
    }
}
