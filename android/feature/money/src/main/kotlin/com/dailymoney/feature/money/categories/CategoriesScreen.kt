package com.dailymoney.feature.money.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.money.categoryIcon
import com.dailymoney.core.designsystem.theme.CategoryPalette
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.DefaultData
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.NameError
import com.dailymoney.core.domain.money.SaveCategoryUseCase
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.message
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryEditor(
    val original: Category?,
    val name: String,
    val iconKey: String,
    val colorIndex: Int,
    val nameError: NameError? = null,
    val inUse: Boolean = false,
)

data class CategoriesUiState(
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val all: List<Category> = emptyList(),
    val editor: CategoryEditor? = null,
) {
    val visible get() = all.filter { it.kind == kind }
}

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoryRepository,
    private val saveCategory: SaveCategoryUseCase,
) : ViewModel() {

    private val kind = MutableStateFlow(CategoryKind.EXPENSE)
    private val editor = MutableStateFlow<CategoryEditor?>(null)

    val state: StateFlow<CategoriesUiState> = combine(repository.observe(), kind, editor) { all, k, e ->
        CategoriesUiState(k, all, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun setKind(k: CategoryKind) {
        kind.value = k
    }

    fun add() {
        editor.value = CategoryEditor(null, "", if (kind.value == CategoryKind.INCOME) "other_income" else "others", 0)
    }

    fun edit(c: Category) {
        editor.value = CategoryEditor(c, c.name, c.iconKey, c.colorIndex)
    }

    fun dismiss() {
        editor.value = null
    }

    fun onName(v: String) = editor.update { it?.copy(name = v, nameError = null) }
    fun onIcon(v: String) = editor.update { it?.copy(iconKey = v) }
    fun onColor(v: Int) = editor.update { it?.copy(colorIndex = v) }

    fun save() {
        val e = editor.value ?: return
        viewModelScope.launch {
            val category = e.original?.copy(name = e.name, iconKey = e.iconKey, colorIndex = e.colorIndex)
                ?: Category(Ids.newId(), e.name, kind.value, e.iconKey, e.colorIndex, isSystem = false)
            val error = saveCategory(category, state.value.all)
            if (error == null) editor.value = null else editor.update { it?.copy(nameError = error) }
        }
    }

    fun delete() {
        val original = editor.value?.original ?: return
        viewModelScope.launch {
            if (repository.deleteIfUnused(original.id)) editor.value = null else editor.update { it?.copy(inUse = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesScreen(onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.money_categories)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.categories_add))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                    listOf(CategoryKind.EXPENSE to R.string.money_expense, CategoryKind.INCOME to R.string.money_income)
                        .forEachIndexed { i, (k, label) ->
                            SegmentedButton(
                                selected = state.kind == k,
                                onClick = { viewModel.setKind(k) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(stringResource(label)) }
                        }
                }
            }
            items(state.visible, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = { if (c.isSystem) Text(stringResource(R.string.categories_default)) },
                    leadingContent = { CategoryBadge(c.iconKey, c.colorIndex) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { viewModel.edit(c) },
                )
            }
        }
    }
    state.editor?.let { CategoryDialog(it, viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(editor: CategoryEditor, viewModel: CategoriesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(if (editor.original == null) R.string.categories_add else R.string.categories_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = viewModel::onName,
                    label = { Text(stringResource(R.string.categories_name)) },
                    isError = editor.nameError != null,
                    supportingText = editor.nameError?.let { { Text(it.message()) } },
                    singleLine = true,
                )
                Text(stringResource(R.string.categories_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CategoryPalette.indices.forEach { i ->
                        val selected = editor.colorIndex == i
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(categoryColor(i), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable(role = Role.RadioButton) { viewModel.onColor(i) },
                        )
                    }
                }
                Text(stringResource(R.string.categories_icon), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DefaultData.iconKeys.forEach { key ->
                        val selected = editor.iconKey == key
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(
                                    if (selected) categoryColor(editor.colorIndex).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                )
                                .clickable(role = Role.RadioButton) { viewModel.onIcon(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(categoryIcon(key), contentDescription = key, tint = categoryColor(editor.colorIndex))
                        }
                    }
                }
                if (editor.inUse) ErrorBanner(stringResource(R.string.categories_in_use))
                if (editor.original != null && !editor.original.isSystem) {
                    TextButton(onClick = viewModel::delete) {
                        Text(stringResource(R.string.categories_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
