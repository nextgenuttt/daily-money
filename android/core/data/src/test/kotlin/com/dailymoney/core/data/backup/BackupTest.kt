package com.dailymoney.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.AppDatabase
import com.dailymoney.core.database.entity.AccountEntity
import com.dailymoney.core.database.entity.TodoEntity
import com.dailymoney.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {

    private val codec = BackupCodec(iterations = 1_000) // fast in tests; production uses 210k

    @Test
    fun `sealed backup opens only with the right password and detects tampering`() {
        val payload = """{"hello":"₹1,23,456"}""".toByteArray()
        val sealed = codec.seal(payload, "correct horse".toCharArray())
        assertTrue(!sealed.toString(Charsets.UTF_8).contains("1,23,456"))
        assertArrayEquals(payload, codec.open(sealed, "correct horse".toCharArray()))

        assertTrue(runCatching { codec.open(sealed, "wrong".toCharArray()) }.exceptionOrNull() is BackupCodec.WrongPasswordException)
        assertTrue(runCatching { codec.open("not json".toByteArray(), "x".toCharArray()) }.exceptionOrNull() is BackupCodec.NotABackupException)

        val tampered = sealed.toString(Charsets.UTF_8).replace("\"data\":\"A", "\"data\":\"B").toByteArray()
        if (!tampered.contentEquals(sealed)) {
            assertTrue(runCatching { codec.open(tampered, "correct horse".toCharArray()) }.isFailure)
        }
    }

    @Test
    fun `snapshot restores every row into a fresh database`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        source.accountDao().upsert(AccountEntity("a1", "Cash", "CASH", "INR", 1000, false, 1, 1))
        source.transactionDao().upsert(
            TransactionEntity(
                id = "t1", creatorId = "u", type = "DEBIT", amountMinor = 25050, currency = "INR", categoryId = null,
                accountId = "a1", toAccountId = null, occurredAt = 5, note = "Lunch \"quoted\" ₹", paymentMethod = null,
                createdAt = 1, updatedAt = 1,
            ),
        )
        source.todoDao().upsert(
            TodoEntity("todo1", "Pay fees", null, "HIGH", null, null, 20_000, null, null, null, "0", null, null, null, 1, 1),
        )
        val snapshot = TableSnapshot.dump(source.openHelper.readableDatabase, 5, 123)
        val json = Json.parseToJsonElement(snapshot.toString()).jsonObject
        source.close()

        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        target.accountDao().upsert(AccountEntity("old", "Old account", "BANK", "INR", 0, false, 1, 1))
        TableSnapshot.restore(target.openHelper.writableDatabase, json)

        assertEquals(listOf("a1"), target.accountDao().observeWithBalances().first().map { it.account.id })
        assertEquals(1000 - 25050L, target.accountDao().observeWithBalances().first().single().balanceMinor)
        assertEquals("Lunch \"quoted\" ₹", target.transactionDao().get("t1")?.note)
        assertEquals("HIGH", target.todoDao().get("todo1")?.priority)
        assertEquals(3, TableSnapshot.rowCount(json))
        target.close()
    }
}
