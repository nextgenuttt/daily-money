package com.dailymoney.feature.money.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.smart.CategoryRule
import com.dailymoney.core.domain.smart.CategoryRuleRepository
import com.dailymoney.core.domain.smart.MerchantKey
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleEditor(val pattern: String = "", val kind: CategoryKind = CategoryKind.EXPENSE, val categoryId: String? = null) {
    /** Needs at least one real word, since matching is by whole words. */
    val valid get() = categoryId != null && MerchantKey.words(pattern).isNotEmpty() && pattern.length <= MAX_PATTERN

    companion object {
        const val MAX_PATTERN = 40
    }
}

data class RulesUiState(
    val rules: List<Pair<CategoryRule, Category?>> = emptyList(),
    val categories: List<Category> = emptyList(),
    val editor: RuleEditor? = null,
)

@HiltViewModel
class CategoryRulesViewModel @Inject constructor(
    private val rules: CategoryRuleRepository,
    categories: CategoryRepository,
) : ViewModel() {

    private val editor = MutableStateFlow<RuleEditor?>(null)

    val state: StateFlow<RulesUiState> = combine(rules.observe(), categories.observe(), editor) { list, cats, e ->
        val byId = cats.associateBy { it.id }
        RulesUiState(list.map { it to byId[it.categoryId] }, cats, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    fun add() {
        editor.value = RuleEditor()
    }

    fun dismiss() {
        editor.value = null
    }

    fun onPattern(v: String) = editor.update { it?.copy(pattern = v.take(RuleEditor.MAX_PATTERN)) }
    fun onKind(k: CategoryKind) = editor.update { if (it?.kind == k) it else it?.copy(kind = k, categoryId = null) }
    fun onCategory(id: String) = editor.update { it?.copy(categoryId = id) }

    fun save() {
        val e = editor.value?.takeIf { it.valid } ?: return
        viewModelScope.launch {
            rules.save(CategoryRule(Ids.newId(), e.pattern.trim(), e.categoryId!!))
            editor.value = null
        }
    }

    fun delete(rule: CategoryRule) {
        viewModelScope.launch { rules.delete(rule.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryRulesScreen(onBack: () -> Unit, viewModel: CategoryRulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rules_add)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    stringResource(R.string.rules_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.screenGutter),
                )
            }
            if (state.rules.isEmpty()) {
                item { EmptyState(Icons.Filled.AutoAwesome, stringResource(R.string.rules_empty_title), stringResource(R.string.rules_empty_body)) }
            }
            items(state.rules, key = { it.first.id }) { (rule, category) ->
                ListItem(
                    headlineContent = { Text("“${rule.pattern}”") },
                    supportingContent = { Text(category?.name ?: stringResource(R.string.rules_category_gone)) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.delete(rule) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rules_delete))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
    state.editor?.let { RuleDialog(it, state.categories, viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(editor: RuleEditor, categories: List<Category>, viewModel: CategoryRulesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.rules_add)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editor.pattern,
                    onValueChange = viewModel::onPattern,
                    label = { Text(stringResource(R.string.rules_pattern)) },
                    placeholder = { Text(stringResource(R.string.rules_pattern_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CategoryKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = editor.kind == k,
                            onClick = { viewModel.onKind(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, CategoryKind.entries.size),
                        ) { Text(stringResource(if (k == CategoryKind.EXPENSE) R.string.money_expense else R.string.money_income)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    categories.filter { it.kind == editor.kind }.forEach { c ->
                        FilterChip(editor.categoryId == c.id, { viewModel.onCategory(c.id) }, { Text(c.name) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save, enabled = editor.valid) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
