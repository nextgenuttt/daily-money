package com.dailymoney.core.domain.money

/** Spec §5 default categories. Names are stored as text so users can rename them. */
object DefaultData {

    data class Seed(val key: String, val name: String, val kind: CategoryKind, val colorIndex: Int)

    val categories = listOf(
        Seed("food", "Food", CategoryKind.EXPENSE, 1),
        Seed("groceries", "Groceries", CategoryKind.EXPENSE, 3),
        Seed("transport", "Transportation", CategoryKind.EXPENSE, 0),
        Seed("fuel", "Fuel", CategoryKind.EXPENSE, 2),
        Seed("shopping", "Shopping", CategoryKind.EXPENSE, 4),
        Seed("rent", "Rent", CategoryKind.EXPENSE, 5),
        Seed("electricity", "Electricity", CategoryKind.EXPENSE, 2),
        Seed("internet", "Internet", CategoryKind.EXPENSE, 0),
        Seed("mobile", "Mobile Recharge", CategoryKind.EXPENSE, 3),
        Seed("medical", "Medical", CategoryKind.EXPENSE, 1),
        Seed("education", "Education", CategoryKind.EXPENSE, 4),
        Seed("entertainment", "Entertainment", CategoryKind.EXPENSE, 1),
        Seed("travel", "Travel", CategoryKind.EXPENSE, 0),
        Seed("insurance", "Insurance", CategoryKind.EXPENSE, 5),
        Seed("emi", "EMI", CategoryKind.EXPENSE, 4),
        Seed("family", "Family", CategoryKind.EXPENSE, 3),
        Seed("personal", "Personal", CategoryKind.EXPENSE, 2),
        Seed("others", "Others", CategoryKind.EXPENSE, 5),
        Seed("salary", "Salary", CategoryKind.INCOME, 3),
        Seed("business", "Business", CategoryKind.INCOME, 0),
        Seed("interest", "Interest", CategoryKind.INCOME, 4),
        Seed("gift", "Gift", CategoryKind.INCOME, 1),
        Seed("refund", "Refund", CategoryKind.INCOME, 2),
        Seed("other_income", "Other income", CategoryKind.INCOME, 5),
    )

    /** Icon keys a user can pick for a custom category (mapped to icons in the design system). */
    val iconKeys = listOf(
        "food", "groceries", "transport", "fuel", "shopping", "rent", "electricity", "internet", "mobile",
        "medical", "education", "entertainment", "travel", "insurance", "emi", "family", "personal", "others",
        "salary", "business", "interest", "gift", "refund", "other_income",
    )
}
