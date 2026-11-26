package com.dailymoney.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One-choice picker that takes a single row however many options there are (categories, accounts,
 * frequencies). [options] may include null as a real choice ("Never", "No account"); when [selected]
 * isn't one of the options the field shows [placeholder].
 */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    optionIcon: (@Composable (T) -> Unit)? = null,
    placeholder: String = "",
    error: String? = null,
    enabled: Boolean = true,
    /** An extra last row, e.g. "+ New category". */
    footer: Pair<String, () -> Unit>? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = options.indexOf(selected) >= 0
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (chosen) optionLabel(selected) else "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = if (placeholder.isNotEmpty()) { { Text(placeholder) } } else null,
            leadingIcon = if (chosen && optionIcon != null) { { optionIcon(selected) } } else null,
            trailingIcon = { Icon(if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, contentDescription = null) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        // The text field is read-only; this layer opens the menu and is announced as a button.
        Box(Modifier.matchParentSize().clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = label) { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = optionIcon?.let { icon -> { icon(option) } },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
            footer?.let { (text, action) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}
