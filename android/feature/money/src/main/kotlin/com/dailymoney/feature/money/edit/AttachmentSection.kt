package com.dailymoney.feature.money.edit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dailymoney.core.domain.money.Attachment
import com.dailymoney.feature.money.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receipts row on the transaction screen: take a photo, pick a photo, or pick a PDF. */
@Composable
internal fun AttachmentSection(
    attachments: List<Attachment>,
    read: suspend (Attachment) -> ByteArray?,
    viewableUri: suspend (Attachment) -> String?,
    onAdd: (Uri) -> Unit,
    onRemove: (Attachment) -> Unit,
    message: String?,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val scope = rememberCoroutineScope()

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraFile ?: return@rememberLauncherForActivityResult
        if (ok) onAdd(Uri.fromFile(File(path))) else File(path).delete()
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onAdd) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onAdd) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.receipts), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(attachments, key = { it.id }) { a ->
                AttachmentTile(a, read, onOpen = {
                    if (a.isImage) viewing = a else scope.launch { viewableUri(a)?.let { openExternally(context, it, a.mimeType) } }
                }, onRemove = { onRemove(a) })
            }
            item {
                Box {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.AddAPhoto, contentDescription = stringResource(R.string.receipts_add))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.receipts_take_photo)) }, onClick = {
                            menuOpen = false
                            val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "receipt_${System.currentTimeMillis()}.jpg")
                            cameraFile = file.absolutePath
                            takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.receipts_choose_photo)) }, onClick = {
                            menuOpen = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.receipts_choose_pdf)) }, onClick = {
                            menuOpen = false
                            pickPdf.launch(arrayOf("application/pdf"))
                        })
                    }
                }
            }
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    viewing?.let { a ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val image by rememberImage(a, read, maxSide = 2000)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable { viewing = null }, contentAlignment = Alignment.Center) {
                image?.let { Image(it, contentDescription = stringResource(R.string.receipts), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
                IconButton(onClick = { viewing = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(attachment: Attachment, read: suspend (Attachment) -> ByteArray?, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(72.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.isImage) {
                val thumb by rememberImage(attachment, read, maxSide = 240)
                thumb?.let { Image(it, contentDescription = stringResource(R.string.receipts), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            } else {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = stringResource(R.string.receipts_pdf), tint = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.receipts_remove),
                modifier = Modifier.size(18.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)),
            )
        }
    }
}

/** Decodes a downsampled bitmap off the main thread. */
@Composable
private fun rememberImage(attachment: Attachment, read: suspend (Attachment) -> ByteArray?, maxSide: Int): State<ImageBitmap?> {
    val image = remember(attachment.id, maxSide) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.id, maxSide) { image.value = read(attachment)?.let { decode(it, maxSide) } }
    return image
}

/** Receipts are decrypted into memory only; this decodes a downsampled bitmap off the main thread. */
private suspend fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private fun openExternally(context: Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No PDF viewer installed; nothing else we can do on the phone.
    }
}
