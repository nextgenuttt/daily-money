package com.dailymoney.core.data.smart

import android.content.Context
import android.net.Uri
import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.CategoryRuleEntity
import com.dailymoney.core.domain.smart.CategoryRule
import com.dailymoney.core.domain.smart.CategoryRuleRepository
import com.dailymoney.core.domain.smart.OcrLine
import com.dailymoney.core.domain.smart.ReceiptTextReader
import com.dailymoney.core.domain.smart.StatementFileReader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * ML Kit text recognition through Google Play services: the model is downloaded once by Play services,
 * then runs on the phone; the photo never leaves the device.
 */
@Singleton
class MlKitReceiptReader @Inject constructor(@ApplicationContext private val context: Context) : ReceiptTextReader {

    override suspend fun read(uri: String): List<OcrLine>? = runCatching {
        val image = InputImage.fromFilePath(context, Uri.parse(uri))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            recognizer.process(image).await().textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
            }
        } finally {
            recognizer.close()
        }
    }.getOrNull()
}

/** Reads a CSV the user picked. Bank exports are UTF-8 or, from older systems, Windows-1252. */
@Singleton
class ContentStatementReader @Inject constructor(@ApplicationContext private val context: Context) : StatementFileReader {

    override suspend fun readText(uri: String): String? = io {
        runCatching {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val data = input.readNBytesCompat(MAX_BYTES + 1)
                if (data.size > MAX_BYTES) return@runCatching null
                data
            } ?: return@runCatching null
            decode(bytes, Charsets.UTF_8) ?: String(bytes, Charset.forName("windows-1252"))
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        /** A year of a busy account is well under this. */
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}

@Singleton
class RoomCategoryRuleRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : CategoryRuleRepository {

    override fun observe(): Flow<List<CategoryRule>> = db.stream { categoryRuleDao().observeAll() }
        .map { rows -> rows.map { CategoryRule(it.id, it.pattern, it.categoryId) } }

    override suspend fun save(rule: CategoryRule) = io {
        db.get().categoryRuleDao().upsert(CategoryRuleEntity(rule.id, rule.pattern.trim(), rule.categoryId, clock.millis()))
    }

    override suspend fun delete(id: String) = io { db.get().categoryRuleDao().delete(id) }
}
