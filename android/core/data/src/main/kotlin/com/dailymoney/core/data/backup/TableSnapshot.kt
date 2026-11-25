package com.dailymoney.core.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Copies whole tables to/from JSON, column by column, so the backup keeps working as tables gain
 * columns. Restores replace the tables inside one transaction: either everything is restored or nothing.
 */
object TableSnapshot {

    /** Personal data tables. Sync outbox, receipt rows and alert bookkeeping are deliberately left out. */
    val TABLES = listOf(
        "accounts", "categories", "transactions", "budgets", "recurring_rules", "savings_goals", "goal_contributions",
        "debts", "debt_repayments", "reminders", "reminder_steps", "reminder_occurrences", "todos", "todo_checklist_items",
        "todo_activity", "category_rules",
    )

    fun dump(db: SupportSQLiteDatabase, schemaVersion: Int, createdAtMillis: Long): JsonObject = buildJsonObject {
        put("schema", JsonPrimitive(schemaVersion))
        put("createdAt", JsonPrimitive(createdAtMillis))
        put("tables", buildJsonObject {
            TABLES.forEach { table ->
                db.query("SELECT * FROM `$table`").use { cursor -> put(table, rows(cursor)) }
            }
        })
    }

    fun rowCount(snapshot: JsonObject): Int = snapshot["tables"]?.jsonObject?.values?.sumOf { it.jsonArray.size } ?: 0

    fun schemaOf(snapshot: JsonObject): Int = snapshot["schema"]?.jsonPrimitive?.int ?: 0

    fun restore(db: SupportSQLiteDatabase, snapshot: JsonObject) {
        val tables = snapshot["tables"]?.jsonObject ?: error("No tables")
        db.beginTransaction()
        try {
            // These tables have no SQL foreign keys, so delete/insert order doesn't matter.
            TABLES.forEach { db.execSQL("DELETE FROM `$it`") }
            TABLES.forEach { table ->
                tables[table]?.jsonArray?.forEach { row ->
                    db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values(row.jsonObject))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun rows(cursor: Cursor): JsonArray = buildJsonArray {
        while (cursor.moveToNext()) {
            add(buildJsonObject {
                for (i in 0 until cursor.columnCount) {
                    val value = when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(i))
                        else -> JsonPrimitive(cursor.getString(i))
                    }
                    put(cursor.getColumnName(i), value)
                }
            })
        }
    }

    private fun values(row: JsonObject) = ContentValues().apply {
        row.forEach { (column, value) ->
            when {
                value is JsonNull -> putNull(column)
                value.jsonPrimitive.isString -> put(column, value.jsonPrimitive.content)
                value.jsonPrimitive.longOrNull != null -> put(column, value.jsonPrimitive.longOrNull)
                value.jsonPrimitive.doubleOrNull != null -> put(column, value.jsonPrimitive.doubleOrNull)
                else -> put(column, value.jsonPrimitive.content)
            }
        }
    }
}
