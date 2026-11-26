package com.dailymoney.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import com.dailymoney.core.designsystem.R

/**
 * A list row that opens on tap and offers Edit / Delete on long press (delete asks first). [content]
 * gets the modifier that handles both. Screen readers get Edit and Delete as row actions too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordActions(
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteTitle: String,
    deleteBody: String,
    content: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val editLabel = stringResource(R.string.record_edit)
    val deleteLabel = stringResource(R.string.record_delete)
    Box {
        content(
            Modifier
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onLongClickLabel = stringResource(R.string.record_more_actions),
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(editLabel) { onEdit(); true },
                        CustomAccessibilityAction(deleteLabel) { confirm = true; true },
                    )
                },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(editLabel) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    confirm = true
                },
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.record_cancel)) } },
        )
    }
}
