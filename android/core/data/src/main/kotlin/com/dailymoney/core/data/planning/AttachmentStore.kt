package com.dailymoney.core.data.planning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.AttachmentEntity
import com.dailymoney.core.domain.money.Attachment
import com.dailymoney.core.domain.money.AttachmentRepository
import com.dailymoney.core.domain.money.AttachmentResult
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.security.FileCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Receipts live in the app's private files folder, encrypted (FileCipher), and are deleted at sign-out.
 * Photos are shrunk to 1600 px WebP on the phone (doc 05 §B.3) so storage and later uploads stay small.
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: DatabaseManager,
    private val clock: Clock,
    private val cipher: FileCipher,
) : AttachmentRepository {

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    override fun observeFor(entityId: String): Flow<List<Attachment>> =
        db.stream { attachmentDao().observeFor(entityId) }.map { rows -> rows.map { it.toDomain() } }

    override suspend fun import(entityId: String, sourceUri: String): AttachmentResult = io {
        val uri = Uri.parse(sourceUri)
        val mime = context.contentResolver.getType(uri) ?: guessMime(sourceUri)
        val id = Ids.newId()
        try {
            val (file, type) = when {
                mime?.startsWith("image/") == true -> File(dir, "$id.webp").also { compressImage(uri, it) } to "image/webp"
                mime == PDF -> File(dir, "$id.pdf").also { copyLimited(uri, it) } to PDF
                else -> return@io AttachmentResult.Unsupported
            }
            if (file.length() > MAX_BYTES) {
                file.delete()
                return@io AttachmentResult.TooLarge
            }
            val entity = AttachmentEntity(id, "TRANSACTION", entityId, file.name, type, file.length(), clock.millis())
            db.get().attachmentDao().upsert(entity)
            AttachmentResult.Added(entity.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooLargeException) {
            File(dir, "$id.pdf").delete()
            AttachmentResult.TooLarge
        } catch (e: Exception) {
            dir.listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
            AttachmentResult.Failed
        }
    }

    override suspend fun delete(id: String) = io {
        val row = db.get().attachmentDao().get(id) ?: return@io
        File(dir, row.fileName).delete()
        db.get().attachmentDao().delete(id)
    }

    override suspend fun deleteFor(entityId: String) = io {
        db.get().attachmentDao().listFor(entityId).forEach {
            File(dir, it.fileName).delete()
            db.get().attachmentDao().delete(it.id)
        }
    }

    override suspend fun read(attachment: Attachment): ByteArray? = io {
        val file = File(dir, attachment.fileName)
        if (!file.exists()) return@io null
        runCatching { cipher.decrypt(file.inputStream(), attachment.fileName).use { it.readBytes() } }.getOrNull()
    }

    override suspend fun viewableUri(attachment: Attachment): String? = io {
        val bytes = read(attachment) ?: return@io null
        val out = File(File(context.cacheDir, SHARED_DIR).apply { mkdirs() }, attachment.fileName)
        out.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.files", out).toString()
    }

    private fun compressImage(uri: Uri, target: File) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Decode failed")

        val rotation = resolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).rotationDegrees } ?: 0
        }
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val output = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        cipher.encrypt(target.outputStream(), target.name).use { output.compress(format, QUALITY, it) }
        if (output !== decoded) output.recycle()
        decoded.recycle()
    }

    private fun copyLimited(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            cipher.encrypt(target.outputStream(), target.name).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw TooLargeException()
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun guessMime(uri: String): String? = when {
        uri.endsWith(".jpg", true) || uri.endsWith(".jpeg", true) -> "image/jpeg"
        uri.endsWith(".png", true) -> "image/png"
        uri.endsWith(".pdf", true) -> PDF
        else -> null
    }

    private fun AttachmentEntity.toDomain() = Attachment(id, entityId, fileName, mimeType, sizeBytes)

    private class TooLargeException : Exception()

    companion object {
        const val DIR = "attachments"
        private const val SHARED_DIR = "shared"
        private const val PDF = "application/pdf"
        private const val MAX_SIDE = 1600
        private const val QUALITY = 75
        private const val MAX_BYTES = 10L * 1024 * 1024
    }
}
