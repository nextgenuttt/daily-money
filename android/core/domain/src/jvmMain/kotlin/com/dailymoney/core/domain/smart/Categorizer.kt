package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.TransactionType
import kotlinx.coroutines.flow.Flow

/** "Anything mentioning [pattern] goes to [categoryId]", set by the user. */
data class CategoryRule(val id: String, val pattern: String, val categoryId: String)

interface CategoryRuleRepository {
    fun observe(): Flow<List<CategoryRule>>
    suspend fun save(rule: CategoryRule)
    suspend fun delete(id: String)
}

/** A past transaction's text and the category the user gave it. */
data class LabelledNote(val note: String, val categoryId: String)

/** Turns bank narrations like "UPI/DR/412345/SWIGGY/YESB/swiggy@ybl" into a short key ("swiggy"). */
object MerchantKey {

    private val STOP = setOf(
        "upi", "dr", "cr", "neft", "imps", "rtgs", "pos", "ref", "txn", "transfer", "trf", "payment", "paid", "pay", "to", "from",
        "by", "the", "and", "for", "via", "bank", "ach", "nach", "ltd", "pvt", "private", "limited", "india", "inr", "rs",
        "debit", "credit", "card", "purchase", "towards", "info", "vpa", "ecom", "mmt", "bil", "onl", "net", "www", "com",
        // IFSC prefixes and UPI handles that show up inside narrations.
        "yesb", "icic", "hdfc", "sbin", "utib", "kkbk", "barb", "punb", "ibkl", "cnrb", "ubin", "idib", "ioba", "fdrl", "indb",
        "ybl", "okaxis", "oksbi", "okicici", "okhdfcbank", "axl", "ibl", "apl",
    )

    fun of(text: String): String = words(text).filter { it.length >= 3 && it !in STOP }.distinct().take(2).joinToString(" ")

    /** Lowercase letters/digits only, so "Dominos-Pizza" and "dominos pizza" read the same; "swiggy@ybl" keeps "swiggy". */
    fun words(text: String): List<String> =
        text.lowercase().replace(Regex("@[a-z0-9.]+"), " ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && !it.all(Char::isDigit) }
}

/**
 * Picks a category for a transaction's text, on the phone (no cloud): the user's own rules first,
 * then what they chose before for the same merchant, then a built-in keyword list.
 */
class Categorizer(
    rules: List<CategoryRule>,
    history: List<LabelledNote>,
    categories: List<Category>,
) {
    private val byId = categories.associateBy { it.id }
    private val rules = rules.filter { it.categoryId in byId }.sortedByDescending { it.pattern.length }
    private val learned: Map<String, String> = history
        .filter { it.categoryId in byId }
        .groupBy { MerchantKey.of(it.note) }
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, notes) -> notes.groupingBy { it.categoryId }.eachCount().maxBy { it.value }.key }

    fun suggest(text: String?, type: TransactionType): Category? {
        if (text.isNullOrBlank() || type == TransactionType.TRANSFER) return null
        val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val padded = " " + MerchantKey.words(text).joinToString(" ") + " "
        fun usable(id: String?) = id?.let(byId::get)?.takeIf { it.kind == kind }

        rules.firstNotNullOfOrNull { rule -> usable(rule.categoryId)?.takeIf { padded.contains(phrase(rule.pattern)) } }?.let { return it }
        usable(learned[MerchantKey.of(text)])?.let { return it }
        return BuiltInKeywords.match(padded, kind)?.let { usable("sys_$it") }
    }

    private fun phrase(pattern: String) = " " + MerchantKey.words(pattern).joinToString(" ") + " "
}

/** Common Indian merchants and words (comma-separated, matched as whole words), keyed by DefaultData category keys. */
internal object BuiltInKeywords {

    private val EXPENSE = mapOf(
        "food" to "swiggy, zomato, restaurant, cafe, dominos, pizza, mcdonalds, kfc, starbucks, bakery, biryani, eatsure, canteen, tea, coffee",
        "groceries" to "bigbasket, blinkit, zepto, dmart, grofers, jiomart, instamart, supermarket, kirana, grocery, groceries, vegetables, reliance fresh",
        "transport" to "uber, ola, rapido, metro, fastag, parking, toll, cab, taxi",
        "fuel" to "petrol, diesel, fuel, hpcl, bpcl, iocl, indianoil, indian oil, bharat petroleum, shell",
        "shopping" to "amazon, flipkart, myntra, ajio, meesho, nykaa, decathlon, ikea, croma, tata cliq",
        "rent" to "rent, landlord, nobroker",
        "electricity" to "electricity, tneb, tangedco, bescom, mseb, msedcl, bses, tata power, adani electricity, eb bill",
        "internet" to "broadband, fibernet, jiofiber, hathway, airtel xstream, wifi, internet",
        "mobile" to "recharge, prepaid, postpaid, jio, airtel, vodafone",
        "medical" to "pharmacy, apollo, medplus, hospital, clinic, 1mg, pharmeasy, netmeds, diagnostics, medical, chemist",
        "education" to "school, college, tuition, udemy, coursera, byjus",
        "entertainment" to "netflix, hotstar, spotify, bookmyshow, pvr, inox, sonyliv, zee5, prime video, youtube premium",
        "travel" to "irctc, makemytrip, goibibo, indigo, air india, vistara, redbus, oyo, cleartrip, airbnb, ixigo, akasa",
        "insurance" to "insurance, lic, policybazaar, star health, hdfc life, icici pru",
        "emi" to "emi, loan, bajaj finance",
    )
    private val INCOME = mapOf(
        "salary" to "salary, payroll",
        "interest" to "interest, int pd",
        "refund" to "refund, reversal, cashback",
    )

    // Longest first, so "airtel xstream" (internet) wins over "airtel" (mobile).
    private val expense = flatten(EXPENSE)
    private val income = flatten(INCOME)

    /** [padded] is the text's words joined by single spaces, with a space at each end. */
    fun match(padded: String, kind: CategoryKind): String? =
        (if (kind == CategoryKind.INCOME) income else expense).firstOrNull { padded.contains(it.first) }?.second

    private fun flatten(map: Map<String, String>) =
        map.flatMap { (key, words) -> words.split(',').map { " ${it.trim()} " to key } }.sortedByDescending { it.first.length }
}
